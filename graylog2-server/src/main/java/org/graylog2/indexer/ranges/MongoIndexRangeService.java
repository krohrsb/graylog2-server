/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.indexer.ranges;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Uninterruptibles;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import org.bson.types.ObjectId;
import org.graylog2.bindings.providers.MongoJackObjectMapperProvider;
import org.graylog2.database.MongoConnection;
import org.graylog2.database.NotFoundException;
import org.graylog2.events.ClusterEventBus;
import org.graylog2.indexer.esplugin.IndexChangeMonitor;
import org.graylog2.indexer.esplugin.IndicesClosedEvent;
import org.graylog2.indexer.esplugin.IndicesDeletedEvent;
import org.graylog2.indexer.esplugin.IndicesReopenedEvent;
import org.graylog2.indexer.indices.Indices;
import org.graylog2.indexer.searches.TimestampStats;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.mongojack.DBCursor;
import org.mongojack.DBQuery;
import org.mongojack.JacksonDBCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;

public class MongoIndexRangeService implements IndexRangeService {
    private static final Logger LOG = LoggerFactory.getLogger(MongoIndexRangeService.class);
    private static final String COLLECTION_NAME = "index_ranges";

    private final Indices indices;
    private final JacksonDBCollection<MongoIndexRange, ObjectId> collection;
    private final EventBus clusterEventBus;

    @Inject
    public MongoIndexRangeService(MongoConnection mongoConnection,
                                  MongoJackObjectMapperProvider objectMapperProvider,
                                  Indices indices,
                                  EventBus eventBus,
                                  @ClusterEventBus EventBus clusterEventBus) {
        this.indices = indices;
        this.collection = JacksonDBCollection.wrap(
                mongoConnection.getDatabase().getCollection(COLLECTION_NAME),
                MongoIndexRange.class,
                ObjectId.class,
                objectMapperProvider.get());
        this.clusterEventBus = clusterEventBus;

        // This sucks. We need to bridge Elasticsearch's and our own Guice injector.
        IndexChangeMonitor.setEventBus(eventBus);
        eventBus.register(this);
        clusterEventBus.register(this);

        collection.createIndex(new BasicDBObject(MongoIndexRange.FIELD_INDEX_NAME, 1));
        collection.createIndex(BasicDBObjectBuilder.start()
                .add(MongoIndexRange.FIELD_BEGIN, 1)
                .add(MongoIndexRange.FIELD_END, 1)
                .get());
    }

    @Override
    public IndexRange get(String index) throws NotFoundException {
        final DBQuery.Query query = DBQuery.and(
                DBQuery.notExists("start"),
                DBQuery.is(IndexRange.FIELD_INDEX_NAME, index));
        final MongoIndexRange indexRange = collection.findOne(query);
        if (indexRange == null) {
            throw new NotFoundException("Index range for index <" + index + "> not found.");
        }

        return indexRange;
    }

    @Override
    public SortedSet<IndexRange> find(DateTime begin, DateTime end) {
        final DBCursor<MongoIndexRange> indexRanges = collection.find(
                DBQuery.and(
                        DBQuery.notExists("start"),  // "start" has been used by the old index ranges in MongoDB
                        DBQuery.lessThanEquals(IndexRange.FIELD_BEGIN, end.getMillis()),
                        DBQuery.greaterThanEquals(IndexRange.FIELD_END, begin.getMillis())
                )
        );

        return ImmutableSortedSet.copyOf(IndexRange.COMPARATOR, (Iterator<? extends IndexRange>) indexRanges);
    }

    @Override
    public SortedSet<IndexRange> findAll() {
        return ImmutableSortedSet.copyOf(IndexRange.COMPARATOR, (Iterator<? extends IndexRange>) collection.find(DBQuery.notExists("start")));
    }

    @Override
    public IndexRange calculateRange(String index) {
        final Stopwatch sw = Stopwatch.createStarted();
        final DateTime now = DateTime.now(DateTimeZone.UTC);
        final TimestampStats stats = indices.timestampStatsOfIndex(index);
        final int duration = Ints.saturatedCast(sw.stop().elapsed(TimeUnit.MILLISECONDS));

        LOG.info("Calculated range of [{}] in [{}ms].", index, duration);
        return MongoIndexRange.create(index, stats.min(), stats.max(), now, duration);
    }

    @Override
    public void save(IndexRange indexRange) {
        collection.remove(DBQuery.in(IndexRange.FIELD_INDEX_NAME, indexRange.indexName()));
        collection.save(MongoIndexRange.create(indexRange));

        clusterEventBus.post(IndexRangeUpdatedEvent.create(indexRange.indexName()));
    }

    @Subscribe
    @AllowConcurrentEvents
    public void handleIndexDeletion(IndicesDeletedEvent event) {
        for (String index : event.indices()) {
            LOG.debug("Index \"{}\" has been deleted. Removing index range.");
            collection.remove(DBQuery.in(IndexRange.FIELD_INDEX_NAME, index));
        }
    }

    @Subscribe
    @AllowConcurrentEvents
    public void handleIndexClosing(IndicesClosedEvent event) {
        for (String index : event.indices()) {
            LOG.debug("Index \"{}\" has been closed. Removing index range.");
            collection.remove(DBQuery.in(IndexRange.FIELD_INDEX_NAME, index));
        }
    }

    @Subscribe
    @AllowConcurrentEvents
    public void handleIndexReopening(IndicesReopenedEvent event) {
        for (String index : event.indices()) {
            LOG.debug("Index \"{}\" has been reopened. Calculating index range.", index);

            indices.waitForRecovery(index);
            // F*ck my life. Seems like Elasticsearch is lying when it says that the cluster health state
            // for this index is YELLOW (or GREEN) or is at least off by a few milliseconds. :-(
            Uninterruptibles.sleepUninterruptibly(250, TimeUnit.MILLISECONDS);

            final IndexRange indexRange = calculateRange(index);
            save(indexRange);
        }
    }
}
