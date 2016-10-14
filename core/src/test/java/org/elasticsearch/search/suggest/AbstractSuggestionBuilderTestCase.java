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

package org.elasticsearch.search.suggest;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalysisService;
import org.elasticsearch.index.analysis.AnalyzerProvider;
import org.elasticsearch.index.analysis.EnglishAnalyzerProvider;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.mapper.core.TextFieldMapper;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.indices.mapper.MapperRegistry;
import org.elasticsearch.indices.query.IndicesQueriesRegistry;
import org.elasticsearch.script.MockScriptEngine;
import org.elasticsearch.script.ScriptContextRegistry;
import org.elasticsearch.script.ScriptEngineRegistry;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.ScriptMode;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.ScriptSettings;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder;
import org.elasticsearch.search.suggest.phrase.DirectCandidateGeneratorBuilder;
import org.elasticsearch.search.suggest.phrase.PhraseSuggestionBuilder;
import org.elasticsearch.search.suggest.term.TermSuggestionBuilder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;

public abstract class AbstractSuggestionBuilderTestCase<SB extends SuggestionBuilder<SB>, CB extends SuggestionSearchContext.SuggestionContext> extends ESTestCase {

    private static final int NUMBER_OF_TESTBUILDERS = 20;
    protected static NamedWriteableRegistry namedWriteableRegistry;
    protected static IndicesQueriesRegistry queriesRegistry;
    protected static ParseFieldMatcher parseFieldMatcher;
    protected static Suggesters suggesters;
    protected static MapperService mapperService;
    private static IndexSettings idxSettings;
    private static AnalysisService analysisService;
    private static SimilarityService similarityService;
    private static ScriptService scriptService;
    private static MapperRegistry mapperRegistry;
    private static Supplier<QueryShardContext> queryShardContextSupplier;
    private static Settings commonSettings;

    private static List<Object> builders = null;

    private List<SB> getRandomTestBuilders() {
        if (builders == null) {
            builders = new LinkedList<>();
            for (int runs = 0; runs < NUMBER_OF_TESTBUILDERS; runs++) {
                builders.add(randomTestBuilder());
            }
        }
        final List<SB> typedBuilders = new LinkedList<>();
        for (Object builderObj : builders) {
            typedBuilders.add((SB) builderObj);
        }
        return typedBuilders;
    }

    private static boolean mapperServiceInitialized = false;

    @Before
    public void initializeMapperService() throws IOException {
        if (!mapperServiceInitialized) {
            Map<String, String> fields = new HashMap<>();
            List<String> analyzers = new LinkedList<>();
            for (SB suggestionBuilder : getRandomTestBuilders()) {
                fields.put(suggestionBuilder.field(), "text");
                if (suggestionBuilder.analyzer() != null) {
                    analyzers.add(suggestionBuilder.analyzer());
                }
                if (suggestionBuilder instanceof PhraseSuggestionBuilder) {
                    PhraseSuggestionBuilder psb = (PhraseSuggestionBuilder) suggestionBuilder;
                    Map<String, List<PhraseSuggestionBuilder.CandidateGenerator>> generators = psb.getCandidateGenerators();
                    if (generators != null && generators.size() > 0) {
                        for (Map.Entry<String, List<PhraseSuggestionBuilder.CandidateGenerator>> generatorEntry : generators.entrySet()) {
                            for (PhraseSuggestionBuilder.CandidateGenerator generator : generatorEntry.getValue()) {
                                if (generator instanceof DirectCandidateGeneratorBuilder) {
                                    DirectCandidateGeneratorBuilder genBuilder = (DirectCandidateGeneratorBuilder) generator;
                                    String preFilter = genBuilder.getPreFilter();
                                    if (preFilter != null) {
                                        analyzers.add(preFilter);
                                    }
                                    String postFilter = genBuilder.getPostFilter();
                                    if (postFilter != null) {
                                        analyzers.add(postFilter);
                                    }
                                }
                            }
                        }
                    }
                } else if (suggestionBuilder instanceof CompletionSuggestionBuilder) {
                    CompletionSuggestionBuilder csb = (CompletionSuggestionBuilder) suggestionBuilder;
                    csb.getPayloadFields();
                }
            }
            resetMapperService(analyzers, fields);
            mapperServiceInitialized = true;
        }
    }

    /**
     * setup for the whole base test class
     */
    @BeforeClass
    public static void init() throws IOException {
        namedWriteableRegistry = new NamedWriteableRegistry();
        SearchModule searchModule = new SearchModule(Settings.EMPTY, namedWriteableRegistry);
        queriesRegistry = searchModule.getQueryParserRegistry();
        suggesters = searchModule.getSuggesters();
        parseFieldMatcher = ParseFieldMatcher.STRICT;

        commonSettings = Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT).
            put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build();

        Settings indexSettings = Settings.builder()
            .put(commonSettings).build();
        Index index = new Index(randomAsciiOfLengthBetween(1, 10), "_na_");
        idxSettings = IndexSettingsModule.newIndexSettings(index, indexSettings);

        SuggestBuilder suggestBuilder = new SuggestBuilder();
        suggestBuilder.setGlobalText(randomAsciiOfLengthBetween(10, 20));
        TermSuggestionBuilder suggestionBuilder = new TermSuggestionBuilder("field1");
        suggestBuilder.addSuggestion("test_suggest", suggestionBuilder);

        IndicesQueriesRegistry indicesQueriesRegistry = new IndicesQueriesRegistry();

        similarityService = new SimilarityService(idxSettings, Collections.emptyMap());

        Map<String, Mapper.TypeParser> mapperParsers = new HashMap<>();
        mapperParsers.put(TextFieldMapper.CONTENT_TYPE, new TextFieldMapper.TypeParser());
        Map<String, MetadataFieldMapper.TypeParser> metadataParsers = new HashMap<>();
        metadataParsers.put(ParentFieldMapper.CONTENT_TYPE, new ParentFieldMapper.TypeParser());
        mapperRegistry = new MapperRegistry(mapperParsers, metadataParsers);

        Set<ScriptEngineService> engines = new HashSet<>();
        engines.add(new MockScriptEngine());
        engines.add(new MockMustacheScriptEngine());

        List<ScriptEngineRegistry.ScriptEngineRegistration> registrations = new LinkedList<>();
        registrations.add(new ScriptEngineRegistry.ScriptEngineRegistration(MockScriptEngine.class, MockScriptEngine.NAME, ScriptMode.ON));
        registrations.add(new ScriptEngineRegistry.ScriptEngineRegistration(MockMustacheScriptEngine.class, "mustache", ScriptMode.ON));
        ScriptEngineRegistry scriptEngineRegistry = new ScriptEngineRegistry(registrations);
        ScriptContextRegistry scriptContextRegistry = new ScriptContextRegistry(Collections.emptyList());

        scriptService = new ScriptService(commonSettings, new Environment(commonSettings), engines,
            new ResourceWatcherService(commonSettings, null),
            scriptEngineRegistry,
            scriptContextRegistry,
            new ScriptSettings(scriptEngineRegistry, scriptContextRegistry));

        queryShardContextSupplier = () -> new QueryShardContext(idxSettings, null, null, mapperService, similarityService,
            scriptService, indicesQueriesRegistry, null, null, null, null) {
            @Override
            public MappedFieldType fieldMapper(String name) {
                TextFieldMapper.Builder builder = new TextFieldMapper.Builder(name);
                return builder.build(new Mapper.BuilderContext(idxSettings.getSettings(), new ContentPath(1))).fieldType();
            }
        };

        //resetMapperService(null, null);
    }

    private static class MockMustacheScriptEngine extends MockScriptEngine {
    }

    private static void resetMapperService(List<String> analyzerNames, Map<String, String> fields) throws IOException {

        Map<String, AnalyzerProvider> analyzerProviders = new HashMap<>();
        Settings envSettings = Settings.builder().put(commonSettings).build();
        Settings engAnProvSettings = Settings.builder().put(commonSettings).build();
        analyzerProviders.put("default", new EnglishAnalyzerProvider(idxSettings, new Environment(envSettings), "default", engAnProvSettings));
        if (analyzerNames != null) {
            for (String analyzerName : analyzerNames) {
                analyzerProviders.put(analyzerName, new EnglishAnalyzerProvider(idxSettings, new Environment(envSettings), "default", engAnProvSettings));
            }
        }
        analysisService = new AnalysisService(idxSettings,
            analyzerProviders,
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap()
        );

        mapperService = new MapperService(idxSettings, analysisService,
            similarityService, mapperRegistry, queryShardContextSupplier);

        if (fields != null) {
            XContentBuilder mapperBuilder = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties");
            for (Map.Entry<String, String> field : fields.entrySet()) {
                mapperBuilder.startObject(field.getKey()).field("type", field.getValue()).endObject();
            }
            String mapping = mapperBuilder.endObject().endObject().endObject().string();
            mapperService.merge("type",
                new CompressedXContent(mapping), MapperService.MergeReason.MAPPING_UPDATE, false);
        }
    }

    @AfterClass
    public static void afterClass() throws Exception {
        namedWriteableRegistry = null;
        suggesters = null;
        queriesRegistry = null;
        analysisService = null;
        similarityService = null;
        mapperService = null;
        mapperRegistry = null;
        idxSettings = null;
        commonSettings = null;
        scriptService = null;
    }

    /**
     * Test serialization and deserialization of the suggestion builder
     */
    public void testSerialization() throws IOException {
        for (SB original : getRandomTestBuilders()) {
            SB deserialized = serializedCopy(original);
            assertEquals(deserialized, original);
            assertEquals(deserialized.hashCode(), original.hashCode());
            assertNotSame(deserialized, original);
        }
    }

    /**
     * returns a random suggestion builder, setting the common options randomly
     */
    protected SB randomTestBuilder() {
        SB randomSuggestion = randomSuggestionBuilder();
        return randomSuggestion;
    }

    public static void setCommonPropertiesOnRandomBuilder(SuggestionBuilder<?> randomSuggestion) {
        randomSuggestion.text(randomAsciiOfLengthBetween(2, 20)); // have to set the text because we don't know if the global text was set
        maybeSet(randomSuggestion::prefix, randomAsciiOfLengthBetween(2, 20));
        maybeSet(randomSuggestion::regex, randomAsciiOfLengthBetween(2, 20));
        maybeSet(randomSuggestion::analyzer, randomAsciiOfLengthBetween(2, 20));
        maybeSet(randomSuggestion::size, randomIntBetween(1, 20));
        maybeSet(randomSuggestion::shardSize, randomIntBetween(1, 20));
    }

    /**
     * create a randomized {@link SuggestBuilder} that is used in further tests
     */
    protected abstract SB randomSuggestionBuilder();

    /**
     * Test equality and hashCode properties
     */
    public void testEqualsAndHashcode() throws IOException {
        for (SB firstBuilder : getRandomTestBuilders()) {
            assertFalse("suggestion builder is equal to null", firstBuilder.equals(null));
            assertFalse("suggestion builder is equal to incompatible type", firstBuilder.equals(""));
            assertTrue("suggestion builder is not equal to self", firstBuilder.equals(firstBuilder));
            assertThat("same suggestion builder's hashcode returns different values if called multiple times", firstBuilder.hashCode(),
                    equalTo(firstBuilder.hashCode()));
        final SB mutate = mutate(firstBuilder);
        assertThat("different suggestion builders should not be equal", mutate, not(equalTo(firstBuilder)));

            SB secondBuilder = serializedCopy(firstBuilder);
            assertTrue("suggestion builder is not equal to self", secondBuilder.equals(secondBuilder));
            assertTrue("suggestion builder is not equal to its copy", firstBuilder.equals(secondBuilder));
            assertTrue("equals is not symmetric", secondBuilder.equals(firstBuilder));
            assertThat("suggestion builder copy's hashcode is different from original hashcode", secondBuilder.hashCode(),
                    equalTo(firstBuilder.hashCode()));

            SB thirdBuilder = serializedCopy(secondBuilder);
            assertTrue("suggestion builder is not equal to self", thirdBuilder.equals(thirdBuilder));
            assertTrue("suggestion builder is not equal to its copy", secondBuilder.equals(thirdBuilder));
            assertThat("suggestion builder copy's hashcode is different from original hashcode", secondBuilder.hashCode(),
                    equalTo(thirdBuilder.hashCode()));
            assertTrue("equals is not transitive", firstBuilder.equals(thirdBuilder));
            assertThat("suggestion builder copy's hashcode is different from original hashcode", firstBuilder.hashCode(),
                    equalTo(thirdBuilder.hashCode()));
            assertTrue("equals is not symmetric", thirdBuilder.equals(secondBuilder));
            assertTrue("equals is not symmetric", thirdBuilder.equals(firstBuilder));
        }
    }

    /**
     * creates random suggestion builder, renders it to xContent and back to new
     * instance that should be equal to original
     */
    public void testFromXContent() throws IOException {
        for (SB suggestionBuilder : getRandomTestBuilders()) {
            XContentBuilder xContentBuilder = XContentFactory.contentBuilder(randomFrom(XContentType.values()));
            if (randomBoolean()) {
                xContentBuilder.prettyPrint();
            }
            xContentBuilder.startObject();
            suggestionBuilder.toXContent(xContentBuilder, ToXContent.EMPTY_PARAMS);
            xContentBuilder.endObject();

            XContentBuilder shuffled = shuffleXContent(xContentBuilder, shuffleProtectedFields());
            XContentParser parser = XContentHelper.createParser(shuffled.bytes());
            QueryParseContext context = new QueryParseContext(queriesRegistry, parser, parseFieldMatcher);
            // we need to skip the start object and the name, those will be parsed by outer SuggestBuilder
            parser.nextToken();

            SuggestionBuilder<?> secondSuggestionBuilder = SuggestionBuilder.fromXContent(context, suggesters);
            assertNotSame(suggestionBuilder, secondSuggestionBuilder);
            assertEquals(suggestionBuilder, secondSuggestionBuilder);
            assertEquals(suggestionBuilder.hashCode(), secondSuggestionBuilder.hashCode());
        }
    }

    public void testSearchContext() throws IOException {
        for (SB suggestionBuilder : getRandomTestBuilders()) {
            final CB context = (CB) suggestionBuilder.build(queryShardContextSupplier.get());
            assertCommonSuggestionSearchContext(suggestionBuilder, context);
            assertSuggestionSearchContext(suggestionBuilder, context);
        }
    }

    private void assertCommonSuggestionSearchContext(SB suggestionBuilder, CB context) {
        assertSame(suggestionBuilder.field(), context.getField());
        final String analyzer = suggestionBuilder.analyzer();
        if (!Strings.isNullOrEmpty(analyzer)) {
            assertThat(context.getAnalyzer(), instanceOf(NamedAnalyzer.class));
            assertSame(analyzer, ((NamedAnalyzer) context.getAnalyzer()).name());
        }

        assertEquals(suggestionBuilder.text(), context.getText().utf8ToString());

        final BytesRef ctxRegex = context.getRegex();
        if (ctxRegex != null) {
            assertEquals(suggestionBuilder.regex(), ctxRegex.utf8ToString());
        } else {
            assertNull(suggestionBuilder.regex());
        }

        final BytesRef ctxPrefix = context.getPrefix();
        if (ctxPrefix != null) {
            if (suggestionBuilder.prefix() != null) {
                assertEquals(suggestionBuilder.prefix(), ctxPrefix.utf8ToString());
            } else {
                assertEquals(suggestionBuilder.text(), ctxPrefix.utf8ToString());
            }
        } else {
            assertNull(suggestionBuilder.text());
            assertNull(suggestionBuilder.prefix());
        }

        if (suggestionBuilder.size() != null) {
            assertSame(suggestionBuilder.size(), context.getSize());
        } else {
            assertEquals(SuggestionSearchContext.SuggestionContext.DEFAULT_SIZE, context.getSize());
        }

        if (suggestionBuilder.shardSize() != null) {
            assertSame(suggestionBuilder.shardSize(), context.getShardSize());
        } else {
            assertSame(Math.max(context.getSize(), SuggestionBuilder.MIN_SHARD_SIZE), context.getShardSize());
        }
    }

    protected abstract void assertSuggestionSearchContext(SB suggestionBuilder, CB context);

    /**
     * Subclasses can override this method and return a set of fields which should be protected from
     * recursive random shuffling in the {@link #testFromXContent()} test case
     */
    protected String[] shuffleProtectedFields() {
        return new String[0];
    }

    private SB mutate(SB firstBuilder) throws IOException {
        SB mutation = serializedCopy(firstBuilder);
        assertNotSame(mutation, firstBuilder);
        // change ither one of the shared SuggestionBuilder parameters, or delegate to the specific tests mutate method
        if (randomBoolean()) {
            switch (randomIntBetween(0, 5)) {
            case 0:
                mutation.text(randomValueOtherThan(mutation.text(), () -> randomAsciiOfLengthBetween(2, 20)));
                break;
            case 1:
                mutation.prefix(randomValueOtherThan(mutation.prefix(), () -> randomAsciiOfLengthBetween(2, 20)));
                break;
            case 2:
                mutation.regex(randomValueOtherThan(mutation.regex(), () -> randomAsciiOfLengthBetween(2, 20)));
                break;
            case 3:
                mutation.analyzer(randomValueOtherThan(mutation.analyzer(), () -> randomAsciiOfLengthBetween(2, 20)));
                break;
            case 4:
                mutation.size(randomValueOtherThan(mutation.size(), () -> randomIntBetween(1, 20)));
                break;
            case 5:
                mutation.shardSize(randomValueOtherThan(mutation.shardSize(), () -> randomIntBetween(1, 20)));
                break;
            }
        } else {
            mutateSpecificParameters(mutation);
        }
        return mutation;
    }

    /**
     * take and input {@link SuggestBuilder} and return another one that is
     * different in one aspect (to test non-equality)
     */
    protected abstract void mutateSpecificParameters(SB firstBuilder) throws IOException;

    @SuppressWarnings("unchecked")
    protected SB serializedCopy(SB original) throws IOException {
        try (BytesStreamOutput output = new BytesStreamOutput()) {
            output.writeNamedWriteable(original);
            try (StreamInput in = new NamedWriteableAwareStreamInput(StreamInput.wrap(output.bytes()), namedWriteableRegistry)) {
                return (SB) in.readNamedWriteable(SuggestionBuilder.class);
            }
        }
    }

    protected static QueryParseContext newParseContext(final String xcontent) throws IOException {
        XContentParser parser = XContentFactory.xContent(xcontent).createParser(xcontent);
        final QueryParseContext parseContext = new QueryParseContext(queriesRegistry, parser, parseFieldMatcher);
        return parseContext;
    }
}
