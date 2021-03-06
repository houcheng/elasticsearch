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

package org.elasticsearch.index.mapper;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.analysis.NamedAnalyzer;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeIntegerValue;
import static org.elasticsearch.index.mapper.TypeParsers.parseField;

/**
 * A {@link FieldMapper} that takes a string and writes the integer of the token values
 * for indexing.  In most ways the mapper acts just like an {@link NumberFieldMapper}.
 */
public class TokenSumFieldMapper extends FieldMapper {
    public static final String CONTENT_TYPE = "token_sum";

    public static class Defaults {
        public static final MappedFieldType FIELD_TYPE = new NumberFieldMapper.NumberFieldType(NumberFieldMapper.NumberType.INTEGER);
    }

    public static class Builder extends FieldMapper.Builder<Builder, TokenSumFieldMapper> {
        private NamedAnalyzer analyzer;

        public Builder(String name) {
            super(name, Defaults.FIELD_TYPE, Defaults.FIELD_TYPE);
            builder = this;
        }

        public Builder analyzer(NamedAnalyzer analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public NamedAnalyzer analyzer() {
            return analyzer;
        }

        @Override
        public TokenSumFieldMapper build(BuilderContext context) {
            setupFieldType(context);
            return new TokenSumFieldMapper(name, fieldType, defaultFieldType,
                    context.indexSettings(), analyzer, multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        @SuppressWarnings("unchecked")
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            TokenSumFieldMapper.Builder builder = new TokenSumFieldMapper.Builder(name);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String propName = entry.getKey();
                Object propNode = entry.getValue();
                if (propName.equals("null_value")) {
                    builder.nullValue(nodeIntegerValue(propNode));
                    iterator.remove();
                } else if (propName.equals("analyzer")) {
                    NamedAnalyzer analyzer = parserContext.getIndexAnalyzers().get(propNode.toString());
                    if (analyzer == null) {
                        throw new MapperParsingException("Analyzer [" + propNode.toString() + "] not found for field [" + name + "]");
                    }
                    builder.analyzer(analyzer);
                    iterator.remove();
                }
            }
            parseField(builder, name, node, parserContext);
            if (builder.analyzer() == null) {
                throw new MapperParsingException("Analyzer must be set for field [" + name + "] but wasn't.");
            }
            return builder;
        }
    }

    private NamedAnalyzer analyzer;
    protected TokenSumFieldMapper(String simpleName, MappedFieldType fieldType, MappedFieldType defaultFieldType,
                                  Settings indexSettings, NamedAnalyzer analyzer, MultiFields multiFields, CopyTo copyTo) {
        super(simpleName, fieldType, defaultFieldType, indexSettings, multiFields, copyTo);
        this.analyzer = analyzer;
    }

    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException {
        final String value;
        if (context.externalValueSet()) {
            value = context.externalValue().toString();
        } else {
            value = context.parser().textOrNull();
        }

        final int tokenSum;

        if (value == null) {
            tokenSum = (Integer) fieldType().nullValue();
        } else {
            tokenSum = calculateSum(analyzer, name(), value);
        }

        boolean indexed = fieldType().indexOptions() != IndexOptions.NONE;
        boolean docValued = fieldType().hasDocValues();
        boolean stored = fieldType().stored();
        fields.addAll(NumberFieldMapper.NumberType.INTEGER.createFields(fieldType().name(), tokenSum, indexed, docValued, stored));
    }

    /**
     * Calculate sum of integer tokens from token stream.
     * @param analyzer analyzer to create token stream
     * @param fieldName field name to pass to analyzer
     * @param fieldValue field value to pass to analyzer
     * @return integer sum of token stream, or -1 if format error
     * @throws IOException if tokenStream throws it
     */
    static int calculateSum(Analyzer analyzer, String fieldName, String fieldValue) throws IOException {
        try (TokenStream tokenStream = analyzer.tokenStream(fieldName, fieldValue)) {
            int sum = 0;
            CharTermAttribute terms = tokenStream.getAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                sum += Integer.parseInt(terms.toString());
            }
            tokenStream.end();
            return sum;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Name of analyzer.
     * @return name of analyzer
     */
    public String analyzer() {
        return analyzer.name();
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    protected void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        super.doMerge(mergeWith, updateAllTypes);
        this.analyzer = ((TokenSumFieldMapper) mergeWith).analyzer;
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);
        builder.field("analyzer", analyzer());
    }

}
