/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.admin.indices.create;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertBlocked;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.IsNull.notNullValue;

@ClusterScope(scope = Scope.TEST)
public class CreateIndexIT extends ESIntegTestCase {

    @Test
    public void testCreationDate_Given() {
        prepareCreate("test").setSettings(Settings.builder().put(IndexMetaData.SETTING_CREATION_DATE, 4l)).get();
        ClusterStateResponse response = client().admin().cluster().prepareState().get();
        ClusterState state = response.getState();
        assertThat(state, notNullValue());
        MetaData metadata = state.getMetaData();
        assertThat(metadata, notNullValue());
        ImmutableOpenMap<String, IndexMetaData> indices = metadata.getIndices();
        assertThat(indices, notNullValue());
        assertThat(indices.size(), equalTo(1));
        IndexMetaData index = indices.get("test");
        assertThat(index, notNullValue());
        assertThat(index.getCreationDate(), equalTo(4l));
    }

    @Test
    public void testCreationDate_Generated() {
        long timeBeforeRequest = System.currentTimeMillis();
        prepareCreate("test").get();
        long timeAfterRequest = System.currentTimeMillis();
        ClusterStateResponse response = client().admin().cluster().prepareState().get();
        ClusterState state = response.getState();
        assertThat(state, notNullValue());
        MetaData metadata = state.getMetaData();
        assertThat(metadata, notNullValue());
        ImmutableOpenMap<String, IndexMetaData> indices = metadata.getIndices();
        assertThat(indices, notNullValue());
        assertThat(indices.size(), equalTo(1));
        IndexMetaData index = indices.get("test");
        assertThat(index, notNullValue());
        assertThat(index.getCreationDate(), allOf(lessThanOrEqualTo(timeAfterRequest), greaterThanOrEqualTo(timeBeforeRequest)));
    }

    @Test
    public void testDoubleAddMapping() throws Exception {
        try {
            prepareCreate("test")
                    .addMapping("type1", "date", "type=date")
                    .addMapping("type1", "num", "type=integer");
            fail("did not hit expected exception");
        } catch (IllegalStateException ise) {
            // expected
        }
        try {
            prepareCreate("test")
                    .addMapping("type1", new HashMap<String,Object>())
                    .addMapping("type1", new HashMap<String,Object>());
            fail("did not hit expected exception");
        } catch (IllegalStateException ise) {
            // expected
        }
        try {
            prepareCreate("test")
                    .addMapping("type1", jsonBuilder())
                    .addMapping("type1", jsonBuilder());
            fail("did not hit expected exception");
        } catch (IllegalStateException ise) {
            // expected
        }
    }

    @Test
    public void testInvalidShardCountSettings() throws Exception {
        try {
            prepareCreate("test").setSettings(Settings.builder()
                    .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, randomIntBetween(-10, 0))
                    .build())
            .get();
            fail("should have thrown an exception about the primary shard count");
        } catch (IllegalArgumentException e) {
            assertThat("message contains error about shard count: " + e.getMessage(),
                    e.getMessage().contains("index must have 1 or more primary shards"), equalTo(true));
        }

        try {
            prepareCreate("test").setSettings(Settings.builder()
                    .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(-10, -1))
                    .build())
                    .get();
            fail("should have thrown an exception about the replica shard count");
        } catch (IllegalArgumentException e) {
            assertThat("message contains error about shard count: " + e.getMessage(),
                    e.getMessage().contains("index must have 0 or more replica shards"), equalTo(true));
        }

        try {
            prepareCreate("test").setSettings(Settings.builder()
                    .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, randomIntBetween(-10, 0))
                    .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(-10, -1))
                    .build())
                    .get();
            fail("should have thrown an exception about the shard count");
        } catch (IllegalArgumentException e) {
            assertThat("message contains error about shard count: " + e.getMessage(),
                    e.getMessage().contains("index must have 1 or more primary shards"), equalTo(true));
            assertThat("message contains error about shard count: " + e.getMessage(),
                    e.getMessage().contains("index must have 0 or more replica shards"), equalTo(true));
        }
    }

    @Test
    public void testCreateIndexWithBlocks() {
        try {
            setClusterReadOnly(true);
            assertBlocked(prepareCreate("test"));
        } finally {
            setClusterReadOnly(false);
        }
    }

    @Test
    public void testCreateIndexWithMetadataBlocks() {
        assertAcked(prepareCreate("test").setSettings(Settings.builder().put(IndexMetaData.SETTING_BLOCKS_METADATA, true)));
        assertBlocked(client().admin().indices().prepareGetSettings("test"), IndexMetaData.INDEX_METADATA_BLOCK);
        disableIndexBlock("test", IndexMetaData.SETTING_BLOCKS_METADATA);
    }

    @Test
    public void testInvalidShardCountSettingsWithoutPrefix() throws Exception {
        try {
            prepareCreate("test").setSettings(Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS.substring(IndexMetaData.INDEX_SETTING_PREFIX.length()), randomIntBetween(-10, 0))
                .build())
                .get();
            fail("should have thrown an exception about the shard count");
        } catch (IllegalArgumentException e) {
            assertThat("message contains error about shard count: " + e.getMessage(),
                e.getMessage().contains("index must have 1 or more primary shards"), equalTo(true));
        }
        try {
            prepareCreate("test").setSettings(Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS.substring(IndexMetaData.INDEX_SETTING_PREFIX.length()), randomIntBetween(-10, -1))
                .build())
                .get();
            fail("should have thrown an exception about the shard count");
        } catch (IllegalArgumentException e) {
            assertThat("message contains error about shard count: " + e.getMessage(),
                e.getMessage().contains("index must have 0 or more replica shards"), equalTo(true));
        }
        try {
            prepareCreate("test").setSettings(Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS.substring(IndexMetaData.INDEX_SETTING_PREFIX.length()), randomIntBetween(-10, 0))
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS.substring(IndexMetaData.INDEX_SETTING_PREFIX.length()), randomIntBetween(-10, -1))
                .build())
                .get();
            fail("should have thrown an exception about the shard count");
        } catch (IllegalArgumentException e) {
            assertThat("message contains error about shard count: " + e.getMessage(),
                e.getMessage().contains("index must have 1 or more primary shards"), equalTo(true));
            assertThat("message contains error about shard count: " + e.getMessage(),
                e.getMessage().contains("index must have 0 or more replica shards"), equalTo(true));
        }
    }

    public void testCreateAndDeleteIndexConcurrently() throws InterruptedException {
        createIndex("test");
        final AtomicInteger indexDeleteAction = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(1);
        int numDocs = randomIntBetween(1, 10);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex("test", "test").setSource("index_version", indexDeleteAction.get()).get();
        }
        synchronized (indexDeleteAction) { // not necessarily needed here
            indexDeleteAction.incrementAndGet();
        }
        client().admin().indices().prepareDelete("test").execute(new ActionListener<DeleteIndexResponse>() { // this happens async!!!
                @Override
                public void onResponse(DeleteIndexResponse deleteIndexResponse) {
                    Thread thread = new Thread() {
                     public void run() {
                         try {
                             client().prepareIndex("test", "test").setSource("index_version", indexDeleteAction.get()).get(); // recreate that index
                             synchronized (indexDeleteAction) {
                                 indexDeleteAction.incrementAndGet();
                             }
                             client().admin().indices().prepareDelete("test").get(); // from here on all docs with index_version == 0|1 must be gone!!!! only 2 are ok;
                         } finally {
                             latch.countDown();
                         }
                     }
                    };
                    thread.start();
                }

                @Override
                public void onFailure(Throwable e) {
                 ExceptionsHelper.reThrowIfNotNull(e);
                }
            }
        );
        numDocs = randomIntBetween(100, 200);
        for (int i = 0; i < numDocs; i++) {
            try {
                synchronized (indexDeleteAction) {
                    client().prepareIndex("test", "test").setSource("index_version", indexDeleteAction.get()).get();
                }
            } catch (IndexNotFoundException inf) {
                // fine
            }
        }
        latch.await();
        refresh();
        SearchResponse expected = client().prepareSearch("test").setIndicesOptions(IndicesOptions.lenientExpandOpen()).setQuery(new RangeQueryBuilder("index_version").from(indexDeleteAction.get(), true)).get();
        SearchResponse all = client().prepareSearch("test").setIndicesOptions(IndicesOptions.lenientExpandOpen()).get();
        assertEquals(expected + " vs. " + all, expected.getHits().getTotalHits(), all.getHits().getTotalHits()
        );
        logger.info("total: {}", expected.getHits().getTotalHits());
    }

}
