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

package org.elasticsearch.search.suggest.phrase;

import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.script.Template;
import org.elasticsearch.search.suggest.AbstractSuggestionBuilderTestCase;
import org.elasticsearch.search.suggest.DirectSpellcheckerSettings;
import org.elasticsearch.search.suggest.SortBy;
import org.elasticsearch.search.suggest.SuggestUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;

public class PhraseSuggestionBuilderTests extends AbstractSuggestionBuilderTestCase<PhraseSuggestionBuilder, PhraseSuggestionContext> {
    @Override
    protected PhraseSuggestionBuilder randomSuggestionBuilder() {
        return randomPhraseSuggestionBuilder();
    }

    @Override
    protected void assertSuggestionSearchContext(PhraseSuggestionBuilder suggestionBuilder, PhraseSuggestionContext context) {
        if (suggestionBuilder.gramSize() != null) {
            assertSame(suggestionBuilder.gramSize(), context.gramSize());
        } else {
            assertSame(PhraseSuggestionContext.DEFAULT_GRAM_SIZE, context.gramSize());
        }

        Map<String, List<PhraseSuggestionBuilder.CandidateGenerator>> generators = suggestionBuilder.getCandidateGenerators();
        if (generators == null || generators.size() == 0) {
            assertThat(context.generators(), hasSize(1));
            PhraseSuggestionContext.DirectCandidateGenerator defaultGenerator = context.generators().get(0);
            assertEquals(suggestionBuilder.field(), defaultGenerator.field());
        } else {
            for (Map.Entry<String, List<PhraseSuggestionBuilder.CandidateGenerator>> entry : generators.entrySet()) {
                if (!entry.getKey().equals(DirectCandidateGeneratorBuilder.TYPE)) {
                    continue;
                }
                final List<PhraseSuggestionBuilder.CandidateGenerator> builderGens = entry.getValue();
                for (int i = 0; i< builderGens.size(); i++) {
                    PhraseSuggestionBuilder.CandidateGenerator builderGen = builderGens.get(i);
                    DirectCandidateGeneratorBuilder expectedGen = (DirectCandidateGeneratorBuilder) builderGen;
                    boolean found = false;
                    for (PhraseSuggestionContext.DirectCandidateGenerator ctxGen : context.generators()) {
                        if (generatorsMatch(expectedGen, ctxGen)) {
                            found = true;
                            break;
                        }
                    }
                    assertTrue(String.format("Did not find direct generator at position %d from builder", i), found);
                }
            }
        }
    }

    private static boolean generatorsMatch(DirectCandidateGeneratorBuilder expectedGen, PhraseSuggestionContext.DirectCandidateGenerator ctxGen) {
        Class<?> expectedDistanceCls;
        if (expectedGen.getStringDistance() != null) {
            expectedDistanceCls = SuggestUtils.resolveDistance(expectedGen.getStringDistance()).getClass();
        } else {
            expectedDistanceCls = DirectSpellcheckerSettings.DEFAULT_STRING_DISTANCE.getClass();
        }

        if (!ctxGen.stringDistance().getClass().equals(expectedDistanceCls)) {
            return false;
        }
        if (!analyzersEqual(expectedGen.getPreFilter(), ctxGen.preFilter())) {
            return false;
        }
        if (!analyzersEqual(expectedGen.getPostFilter(), ctxGen.postFilter())) {
            return false;
        }
        String expectedSuggestMode = expectedGen.getSuggestMode() != null ?
            expectedGen.getSuggestMode() :
            //TODO: constant? needs to be input to SuggestUtils.resolveSuggestMode leading to DirectSpellcheckerSettings.DEFAULT_SUGGEST_MODE
            "missing";
        if (!SuggestUtils.resolveSuggestMode(expectedSuggestMode).equals(ctxGen.suggestMode())) {
            return false;
        }
        if (!floatEqualOrDefault(expectedGen.getAccuracy(), DirectSpellcheckerSettings.DEFAULT_ACCURACY,
            ctxGen.accuracy(), 0.0f)) {
            return false;
        }
        if (!intEqualOrDefault(expectedGen.getMaxEdits(), DirectSpellcheckerSettings.DEFAULT_MAX_EDITS,
            ctxGen.maxEdits())) {
            return false;
        }
        if (!intEqualOrDefault(expectedGen.getSize(), PhraseSuggestionContext.DirectCandidateGenerator.DEFAULT_SIZE,
            ctxGen.size())) {
            return false;
        }
        if (expectedGen.getSort() != null) {
            if (!ctxGen.sort().equals(SortBy.resolve(expectedGen.getSort()))) {
                return false;
            }
        } else if (!ctxGen.sort().equals(DirectSpellcheckerSettings.DEFAULT_SORT)) {
            return false;
        }
        if (!intEqualOrDefault(expectedGen.getMaxInspections(), DirectSpellcheckerSettings.DEFAULT_MAX_INSPECTIONS,
            ctxGen.maxInspections())) {
            return false;
        }
        if (!floatEqualOrDefault(expectedGen.getMaxTermFreq(), DirectSpellcheckerSettings.DEFAULT_MAX_TERM_FREQ,
            ctxGen.maxTermFreq(), 0.0f)) {
            return false;
        }
        if (!intEqualOrDefault(expectedGen.getPrefixLength(), DirectSpellcheckerSettings.DEFAULT_PREFIX_LENGTH,
            ctxGen.prefixLength())) {
            return false;
        }
        if (!intEqualOrDefault(expectedGen.getMinWordLength(), DirectSpellcheckerSettings.DEFAULT_MIN_WORD_LENGTH,
            ctxGen.minWordLength())) {
            return false;
        }
        if (!floatEqualOrDefault(expectedGen.getMinDocFreq(), DirectSpellcheckerSettings.DEFAULT_MIN_DOC_FREQ,
            ctxGen.minDocFreq(), 0.0f)) {
            return false;
        }

        return true;
    }

    private static boolean intEqualOrDefault(Integer expected, int nullValue, int actual) {
        if (expected == null) {
            return actual == nullValue;
        } else {
            return actual == expected.intValue();
        }
    }

    private static boolean floatEqualOrDefault(Float expected, float nullValue, float actual, float tolerance) {
        float expectedVal = expected != null ? expected : nullValue;
        return Math.abs(expectedVal - actual) <= tolerance;
    }

    private static boolean analyzersEqual(String name, Analyzer analyzer) {
        if (name != null) {
            if (analyzer != null &&
                analyzer.equals(mapperService.analysisService().analyzer(name))) {
                return true;
            }
            return false;
        } else {
            return analyzer == null;
        }
    }

    public static PhraseSuggestionBuilder randomPhraseSuggestionBuilder() {
        PhraseSuggestionBuilder testBuilder = new PhraseSuggestionBuilder(randomAsciiOfLengthBetween(2, 20));
        setCommonPropertiesOnRandomBuilder(testBuilder);
        maybeSet(testBuilder::maxErrors, randomFloat());
        maybeSet(testBuilder::separator, randomAsciiOfLengthBetween(1, 10));
        maybeSet(testBuilder::realWordErrorLikelihood, randomFloat());
        maybeSet(testBuilder::confidence, randomFloat());
        maybeSet(testBuilder::collateQuery, randomAsciiOfLengthBetween(3, 20));
        // collate query prune and parameters will only be used when query is set
        if (testBuilder.collateQuery() != null) {
            maybeSet(testBuilder::collatePrune, randomBoolean());
            if (randomBoolean()) {
                Map<String, Object> collateParams = new HashMap<>();
                int numParams = randomIntBetween(1, 5);
                for (int i = 0; i < numParams; i++) {
                    collateParams.put(randomAsciiOfLength(5), randomAsciiOfLength(5));
                }
                testBuilder.collateParams(collateParams );
            }
        }
        if (randomBoolean()) {
            // preTag, postTag
            testBuilder.highlight(randomAsciiOfLengthBetween(3, 20), randomAsciiOfLengthBetween(3, 20));
        }
        maybeSet(testBuilder::gramSize, randomIntBetween(1, 5));
        maybeSet(testBuilder::forceUnigrams, randomBoolean());
        maybeSet(testBuilder::tokenLimit, randomIntBetween(1, 20));
        if (randomBoolean()) {
            testBuilder.smoothingModel(randomSmoothingModel());
        }
        if (randomBoolean()) {
            int numGenerators = randomIntBetween(1, 5);
            for (int i = 0; i < numGenerators; i++) {
                testBuilder.addCandidateGenerator(DirectCandidateGeneratorTests.randomCandidateGenerator());
            }
        }
        return testBuilder;
    }

    private static SmoothingModel randomSmoothingModel() {
        SmoothingModel model = null;
        switch (randomIntBetween(0,2)) {
        case 0:
            model = LaplaceModelTests.createRandomModel();
            break;
        case 1:
            model = StupidBackoffModelTests.createRandomModel();
            break;
        case 2:
            model = LinearInterpolationModelTests.createRandomModel();
            break;
        }
        return model;
    }

    @Override
    protected void mutateSpecificParameters(PhraseSuggestionBuilder builder) throws IOException {
        switch (randomIntBetween(0, 12)) {
        case 0:
            builder.maxErrors(randomValueOtherThan(builder.maxErrors(), () -> randomFloat()));
            break;
        case 1:
            builder.realWordErrorLikelihood(randomValueOtherThan(builder.realWordErrorLikelihood(), () -> randomFloat()));
            break;
        case 2:
            builder.confidence(randomValueOtherThan(builder.confidence(), () -> randomFloat()));
            break;
        case 3:
            builder.gramSize(randomValueOtherThan(builder.gramSize(), () -> randomIntBetween(1, 5)));
            break;
        case 4:
            builder.tokenLimit(randomValueOtherThan(builder.tokenLimit(), () -> randomIntBetween(1, 20)));
            break;
        case 5:
            builder.separator(randomValueOtherThan(builder.separator(), () -> randomAsciiOfLengthBetween(1, 10)));
            break;
        case 6:
            Template collateQuery = builder.collateQuery();
            if (collateQuery != null) {
                builder.collateQuery(randomValueOtherThan(collateQuery.getScript(), () -> randomAsciiOfLengthBetween(3, 20)));
            } else {
                builder.collateQuery(randomAsciiOfLengthBetween(3, 20));
            }
            break;
        case 7:
            builder.collatePrune(builder.collatePrune() == null ? randomBoolean() : !builder.collatePrune() );
            break;
        case 8:
            // preTag, postTag
            String currentPre = builder.preTag();
            if (currentPre != null) {
                // simply double both values
                builder.highlight(builder.preTag() + builder.preTag(), builder.postTag() + builder.postTag());
            } else {
                builder.highlight(randomAsciiOfLengthBetween(3, 20), randomAsciiOfLengthBetween(3, 20));
            }
            break;
        case 9:
            builder.forceUnigrams(builder.forceUnigrams() == null ? randomBoolean() : ! builder.forceUnigrams());
            break;
        case 10:
            Map<String, Object> collateParams = builder.collateParams() == null ? new HashMap<>(1) : builder.collateParams();
            collateParams.put(randomAsciiOfLength(5), randomAsciiOfLength(5));
            builder.collateParams(collateParams);
            break;
        case 11:
            builder.smoothingModel(randomValueOtherThan(builder.smoothingModel(), PhraseSuggestionBuilderTests::randomSmoothingModel));
            break;
        case 12:
            builder.addCandidateGenerator(DirectCandidateGeneratorTests.randomCandidateGenerator());
            break;
        }
    }

    public void testInvalidParameters() throws IOException {
        // test missing field name
        Exception e = expectThrows(NullPointerException.class, () -> new PhraseSuggestionBuilder((String) null));
        assertEquals("suggestion requires a field name", e.getMessage());

        // test empty field name
        e = expectThrows(IllegalArgumentException.class, () -> new PhraseSuggestionBuilder(""));
        assertEquals("suggestion field name is empty", e.getMessage());

        PhraseSuggestionBuilder builder = new PhraseSuggestionBuilder(randomAsciiOfLengthBetween(2, 20));

        e = expectThrows(IllegalArgumentException.class, () -> builder.gramSize(0));
        assertEquals("gramSize must be >= 1", e.getMessage());
        e = expectThrows(IllegalArgumentException.class, () -> builder.gramSize(-1));
        assertEquals("gramSize must be >= 1", e.getMessage());

        e = expectThrows(IllegalArgumentException.class, () -> builder.maxErrors(-1));
        assertEquals("max_error must be > 0.0", e.getMessage());

        e = expectThrows(NullPointerException.class, () -> builder.separator(null));
        assertEquals("separator cannot be set to null", e.getMessage());

        e = expectThrows(IllegalArgumentException.class, () -> builder.realWordErrorLikelihood(-1));
        assertEquals("real_word_error_likelihood must be > 0.0", e.getMessage());

        e = expectThrows(IllegalArgumentException.class, () -> builder.confidence(-1));
        assertEquals("confidence must be >= 0.0", e.getMessage());

        e = expectThrows(IllegalArgumentException.class, () -> builder.tokenLimit(0));
        assertEquals("token_limit must be >= 1", e.getMessage());

        e = expectThrows(IllegalArgumentException.class, () -> builder.highlight(null, "</b>"));
        assertEquals("Pre and post tag must both be null or both not be null.", e.getMessage());

        e = expectThrows(IllegalArgumentException.class, () -> builder.highlight("<b>", null));
        assertEquals("Pre and post tag must both be null or both not be null.", e.getMessage());
    }

}
