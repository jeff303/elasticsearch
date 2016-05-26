package org.elasticsearch.search.suggest;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalysisService;
import org.elasticsearch.index.analysis.AnalyzerProvider;
import org.elasticsearch.index.analysis.EnglishAnalyzerProvider;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.mapper.core.TextFieldMapper;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.indices.mapper.MapperRegistry;
import org.elasticsearch.indices.query.IndicesQueriesRegistry;
import org.elasticsearch.search.suggest.term.TermSuggestionBuilder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.equalTo;

public class SuggestionSearchContextTests extends ESTestCase {

    private MapperService mapperService;

    public void testSuggestionSearchContext() throws IOException {
        Settings commonSettings = Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT).
            put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build();

        Settings indexSettings = Settings.builder()
            .put(commonSettings).build();
        Index index = new Index(randomAsciiOfLengthBetween(1, 10), "_na_");
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings(index, indexSettings);

        SuggestBuilder suggestBuilder = new SuggestBuilder();
        suggestBuilder.setGlobalText(randomAsciiOfLengthBetween(10, 20));
        TermSuggestionBuilder suggestionBuilder = new TermSuggestionBuilder("field1");
        suggestBuilder.addSuggestion("test_suggest", suggestionBuilder);

        IndicesQueriesRegistry indicesQueriesRegistry = new IndicesQueriesRegistry();

        Map<String, AnalyzerProvider> analyzerProviders = new HashMap<>();
        final Settings envSettings = Settings.builder().put(commonSettings).build();
        final Settings engAnProvSettings = Settings.builder().put(commonSettings).build();
        analyzerProviders.put("default", new EnglishAnalyzerProvider(idxSettings, new Environment(envSettings), "default", engAnProvSettings));
        AnalysisService analysisService = new AnalysisService(idxSettings,
            analyzerProviders,
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap()
        );

        SimilarityService similarityService = new SimilarityService(idxSettings, Collections.emptyMap());

        Map<String, Mapper.TypeParser> mapperParsers = new HashMap<>();
        mapperParsers.put(TextFieldMapper.CONTENT_TYPE, new TextFieldMapper.TypeParser());
        Map<String, MetadataFieldMapper.TypeParser> metadataParsers = new HashMap<>();
        metadataParsers.put(ParentFieldMapper.CONTENT_TYPE, new ParentFieldMapper.TypeParser());
        MapperRegistry mapperRegistry = new MapperRegistry(mapperParsers, metadataParsers);

        Supplier<QueryShardContext> supplier = () -> new QueryShardContext(idxSettings, null, null, mapperService, null, null,
            indicesQueriesRegistry, null, null, null, null) {
            @Override
            public MappedFieldType fieldMapper(String name) {
                TextFieldMapper.Builder builder = new TextFieldMapper.Builder(name);
                return builder.build(new Mapper.BuilderContext(idxSettings.getSettings(), new ContentPath(1))).fieldType();
            }
        };

        mapperService = new MapperService(idxSettings, analysisService,
            similarityService, mapperRegistry, supplier);

        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
            .startObject("properties").startObject("field1").field("type", "text").endObject().endObject()
            .endObject().endObject().string();

        mapperService.merge("type",
            new CompressedXContent(mapping), MapperService.MergeReason.MAPPING_UPDATE, false);

        SuggestionSearchContext suggestionSearchContext = suggestBuilder.build(supplier.get());

        assertThat(suggestionSearchContext.suggestions().size(), equalTo(1));

        for (Map.Entry<String, SuggestionSearchContext.SuggestionContext> suggestEntry : suggestionSearchContext.suggestions().entrySet()) {
            final String suggestionName = "test_suggest";
            assertThat(suggestEntry.getKey(), equalTo(suggestionName));
            final SuggestionSearchContext.SuggestionContext suggestionCtx = suggestEntry.getValue();
            // TODO: get this working somehow?  would need to implement equals for QueryShardContext
            //assertThat(suggestionCtx.getShardContext(), equalTo(supplier.get()));
            assertThat(suggestionCtx.getAnalyzer(), equalTo(mapperService.fullName("field1").searchAnalyzer()));
            assertThat(suggestionCtx.getText().utf8ToString(), equalTo(suggestBuilder.getGlobalText()));
            assertThat(suggestionCtx.getField(), equalTo("field1"));
        }
    }
}
