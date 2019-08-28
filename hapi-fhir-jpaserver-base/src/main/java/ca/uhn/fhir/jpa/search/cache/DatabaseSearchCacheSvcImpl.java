package ca.uhn.fhir.jpa.search.cache;

import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.data.ISearchDao;
import ca.uhn.fhir.jpa.dao.data.ISearchIncludeDao;
import ca.uhn.fhir.jpa.dao.data.ISearchResultDao;
import ca.uhn.fhir.jpa.entity.Search;
import ca.uhn.fhir.jpa.entity.SearchInclude;
import ca.uhn.fhir.jpa.model.search.SearchStatusEnum;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DateUtils;
import org.hl7.fhir.dstu3.model.InstantType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.transaction.Transactional;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class DatabaseSearchCacheSvcImpl extends BaseSearchCacheSvcImpl {
	private static final Logger ourLog = LoggerFactory.getLogger(DatabaseSearchCacheSvcImpl.class);

	/*
	 * Be careful increasing this number! We use the number of params here in a
	 * // FIXME KHS
	 * DELETE FROM foo WHERE params IN (aaaa)
	 * type query and this can fail if we have 1000s of params
	 */
	public static final int DEFAULT_MAX_RESULTS_TO_DELETE_IN_ONE_STMT = 500;
	public static final int DEFAULT_MAX_RESULTS_TO_DELETE_IN_ONE_PAS = 20000;
	public static final long DEFAULT_CUTOFF_SLACK = 10 * DateUtils.MILLIS_PER_SECOND;

	private static int ourMaximumResultsToDeleteInOneStatement = DEFAULT_MAX_RESULTS_TO_DELETE_IN_ONE_STMT;
	private static int ourMaximumResultsToDeleteInOnePass = DEFAULT_MAX_RESULTS_TO_DELETE_IN_ONE_PAS;
	private static Long ourNowForUnitTests;
	/*
	 * We give a bit of extra leeway just to avoid race conditions where a query result
	 * is being reused (because a new client request came in with the same params) right before
	 * the result is to be deleted
	 */
	private long myCutoffSlack = DEFAULT_CUTOFF_SLACK;

	@Autowired
	private ISearchDao mySearchDao;
	@Autowired
	private ISearchResultDao mySearchResultDao;
	@Autowired
	private ISearchIncludeDao mySearchIncludeDao;
	@Autowired
	private PlatformTransactionManager myTxManager;
	@Autowired
	private DaoConfig myDaoConfig;

	@VisibleForTesting
	public void setCutoffSlackForUnitTest(long theCutoffSlack) {
		myCutoffSlack = theCutoffSlack;
	}

	@Transactional(Transactional.TxType.REQUIRED)
	@Override
	public Search save(Search theSearch) {
		Search newSearch;
		if (theSearch.getId() == null) {
			newSearch = mySearchDao.save(theSearch);
			for (SearchInclude next : theSearch.getIncludes()) {
				mySearchIncludeDao.save(next);
			}
		} else {
			newSearch = mySearchDao.save(theSearch);
		}
		return newSearch;
	}

	@Override
	@Transactional(Transactional.TxType.REQUIRED)
	public Optional<Search> fetchByUuid(String theUuid) {
		Validate.notBlank(theUuid);
		return mySearchDao.findByUuidAndFetchIncludes(theUuid);
	}


	@Override
	@Transactional(Transactional.TxType.NEVER)
	public Optional<Search> tryToMarkSearchAsInProgress(Search theSearch) {
		ourLog.trace("Going to try to change search status from {} to {}", theSearch.getStatus(), SearchStatusEnum.LOADING);
		try {
			TransactionTemplate txTemplate = new TransactionTemplate(myTxManager);
			txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			txTemplate.afterPropertiesSet();
			return txTemplate.execute(t -> {
				Search search = mySearchDao.findById(theSearch.getId()).orElse(theSearch);

				if (search.getStatus() != SearchStatusEnum.PASSCMPLET) {
					throw new IllegalStateException("Can't change to LOADING because state is " + theSearch.getStatus());
				}
				search.setStatus(SearchStatusEnum.LOADING);
				Search newSearch = mySearchDao.save(search);
				return Optional.of(newSearch);
			});
		} catch (Exception e) {
			ourLog.warn("Failed to activate search: {}", e.toString());
			ourLog.trace("Failed to activate search", e);
			return Optional.empty();
		}
	}

	@Override
	public Collection<Search> findCandidatesForReuse(String theResourceType, String theQueryString, int theQueryStringHash, Date theCreatedAfter) {
		int hashCode = theQueryString.hashCode();
		return mySearchDao.find(theResourceType, hashCode, theCreatedAfter);

	}

	@Override
	protected void flushLastUpdated(Long theSearchId, Date theLastUpdated) {
		mySearchDao.updateSearchLastReturned(theSearchId, theLastUpdated);
	}

	@Transactional(Transactional.TxType.NEVER)
	@Override
	public void pollForStaleSearchesAndDeleteThem() {
		if (!myDaoConfig.isExpireSearchResults()) {
			return;
		}

		long cutoffMillis = myDaoConfig.getExpireSearchResultsAfterMillis();
		if (myDaoConfig.getReuseCachedSearchResultsForMillis() != null) {
			cutoffMillis = Math.max(cutoffMillis, myDaoConfig.getReuseCachedSearchResultsForMillis());
		}
		final Date cutoff = new Date((now() - cutoffMillis) - myCutoffSlack);

		if (ourNowForUnitTests != null) {
			ourLog.info("Searching for searches which are before {} - now is {}", new InstantType(cutoff), new InstantType(new Date(now())));
		}

		ourLog.debug("Searching for searches which are before {}", cutoff);

		TransactionTemplate tt = new TransactionTemplate(myTxManager);
		final Slice<Long> toDelete = tt.execute(theStatus ->
			mySearchDao.findWhereLastReturnedBefore(cutoff, PageRequest.of(0, 2000))
		);
		for (final Long nextSearchToDelete : toDelete) {
			ourLog.debug("Deleting search with PID {}", nextSearchToDelete);
			tt.execute(t -> {
				mySearchDao.updateDeleted(nextSearchToDelete, true);
				return null;
			});

			tt.execute(t -> {
				deleteSearch(nextSearchToDelete);
				return null;
			});
		}

		int count = toDelete.getContent().size();
		if (count > 0) {
			if (ourLog.isDebugEnabled()) {
				long total = tt.execute(t -> mySearchDao.count());
				ourLog.debug("Deleted {} searches, {} remaining", count, total);
			}
		}
	}


	@VisibleForTesting
	void setSearchDaoForUnitTest(ISearchDao theSearchDao) {
		mySearchDao = theSearchDao;
	}

	@VisibleForTesting
	void setSearchDaoIncludeForUnitTest(ISearchIncludeDao theSearchIncludeDao) {
		mySearchIncludeDao = theSearchIncludeDao;
	}

	private void deleteSearch(final Long theSearchPid) {
		mySearchDao.findById(theSearchPid).ifPresent(searchToDelete -> {
			mySearchIncludeDao.deleteForSearch(searchToDelete.getId());

			/*
			 * Note, we're only deleting up to 500 results in an individual search here. This
			 * is to prevent really long running transactions in cases where there are
			 * huge searches with tons of results in them. By the time we've gotten here
			 * we have marked the parent Search entity as deleted, so it's not such a
			 * huge deal to be only partially deleting search results. They'll get deleted
			 * eventually
			 */
			int max = ourMaximumResultsToDeleteInOnePass;
			Slice<Long> resultPids = mySearchResultDao.findForSearch(PageRequest.of(0, max), searchToDelete.getId());
			if (resultPids.hasContent()) {
				List<List<Long>> partitions = Lists.partition(resultPids.getContent(), ourMaximumResultsToDeleteInOneStatement);
				for (List<Long> nextPartition : partitions) {
					mySearchResultDao.deleteByIds(nextPartition);
				}

			}

			// Only delete if we don't have results left in this search
			if (resultPids.getNumberOfElements() < max) {
				ourLog.debug("Deleting search {}/{} - Created[{}] -- Last returned[{}]", searchToDelete.getId(), searchToDelete.getUuid(), new InstantType(searchToDelete.getCreated()), new InstantType(searchToDelete.getSearchLastReturned()));
				mySearchDao.deleteByPid(searchToDelete.getId());
			} else {
				ourLog.debug("Purged {} search results for deleted search {}/{}", resultPids.getSize(), searchToDelete.getId(), searchToDelete.getUuid());
			}
		});
	}

	@VisibleForTesting
	public static void setMaximumResultsToDeleteInOnePassForUnitTest(int theMaximumResultsToDeleteInOnePass) {
		ourMaximumResultsToDeleteInOnePass = theMaximumResultsToDeleteInOnePass;
	}

	@VisibleForTesting
	public static void setMaximumResultsToDeleteForUnitTest(int theMaximumResultsToDelete) {
		ourMaximumResultsToDeleteInOneStatement = theMaximumResultsToDelete;
	}

	/**
	 * This is for unit tests only, do not call otherwise
	 */
	@VisibleForTesting
	public static void setNowForUnitTests(Long theNowForUnitTests) {
		ourNowForUnitTests = theNowForUnitTests;
	}

	private static long now() {
		if (ourNowForUnitTests != null) {
			return ourNowForUnitTests;
		}
		return System.currentTimeMillis();
	}

}
