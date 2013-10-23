package com.qmetric.feed.consumer

import com.amazonaws.services.simpledb.AmazonSimpleDBClient
import com.amazonaws.services.simpledb.model.Item
import com.amazonaws.services.simpledb.model.SelectRequest
import com.qmetric.feed.consumer.store.FeedTracker
import com.qmetric.feed.consumer.store.SimpleDBFeedTracker
import com.qmetric.feed.consumer.utils.MockEntryHandler
import com.qmetric.feed.consumer.utils.MockFeedHandler
import com.qmetric.feed.consumer.utils.SimpleDBUtils
import com.theoryinpractise.halbuilder.api.ReadableRepresentation
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import spark.Spark

import static com.google.common.base.Preconditions.checkState
import static com.qmetric.feed.consumer.DomainNameFactory.userPrefixedDomainName
import static java.lang.System.getenv
import static java.util.concurrent.TimeUnit.SECONDS
import static org.apache.commons.lang3.StringUtils.isBlank
import static org.hamcrest.CoreMatchers.equalTo
import static org.hamcrest.MatcherAssert.assertThat
import static org.mockito.Matchers.any
import static org.mockito.Mockito.*

class IntegrationTest
{
    private static final FEED_SIZE = 9
    private static final PAGE_SIZE = 3
    private static final FEED_SERVER_PORT = 15000
    private static final DOMAIN_NAME = userPrefixedDomainName('hal-feed-consumer-test')
    private final String accessKey
    private final String secretKey
    private final AmazonSimpleDBClient simpleDBClient
    private final FeedTracker tracker

    private final ConsumeAction action = mock(ConsumeAction)

    private final FeedConsumerScheduler consumer

    public IntegrationTest()
    {
        accessKey = getenv('HAL_CONSUMER_IT_AWS_ACCESS_KEY')
        checkState(!isBlank(accessKey), 'Missing env variable %s', 'HAL_CONSUMER_IT_AWS_ACCESS_KEY')
        secretKey = getenv('HAL_CONSUMER_IT_AWS_SECRET_KEY')
        checkState(!isBlank(accessKey), 'Missing env variable %s', 'HAL_CONSUMER_IT_AWS_SECRET_KEY')
        simpleDBClient = new SimpleDBClientFactory(accessKey, secretKey).simpleDBClient()
        tracker = new SimpleDBFeedTracker(simpleDBClient, DOMAIN_NAME)
        consumer = new FeedConsumerConfiguration()
                .consumeEachEntryWith(action)
                .withFeedTracker(tracker)
                .pollForNewEntriesEvery(30, SECONDS)
                .fromUrl("http://localhost:${FEED_SERVER_PORT}/feed").build()
    }

    @BeforeClass public static void startupServer()
    {
        Spark.setPort(FEED_SERVER_PORT)
        Spark.get(new MockFeedHandler("/feed", FEED_SIZE, PAGE_SIZE))
        Spark.get(new MockEntryHandler())
    }

    @Before public void createDomain()
    {

        new SimpleDBUtils(simpleDBClient).createDomainAndWait(DOMAIN_NAME)
    }

    @After public void deleteSimpleDBDomain()
    {
        new SimpleDBUtils(simpleDBClient).deleteDomain(DOMAIN_NAME)
    }


    @Test(timeout = 60000L) public void 'all entries provided by the mock feed are stored '()
    {
        consumer.start()
        waitConsumerToRunOnce(consumer)
        consumer.stop()
        verify(action, times(FEED_SIZE)).consume(any(ReadableRepresentation))
        def result = simpleDBClient.select(new SelectRequest("select * from `${DOMAIN_NAME}`", true))
        assertThat(result.items.size(), equalTo(FEED_SIZE))
        result.items.each { Item it ->
            def attributes = it.attributes.collectEntries { [it.name, it.value] }
            assertThat(attributes.containsKey('consumed'), equalTo(true))
            assertThat(attributes.containsKey('consuming'), equalTo(true))
            assertThat(attributes.containsKey('seen_at'), equalTo(true))
        }
    }

    private static void waitConsumerToRunOnce(FeedConsumerScheduler consumer)
    {
        while (consumer.getInvocationsCount() < 1)
        {
            SECONDS.sleep(5)
        }
    }
}