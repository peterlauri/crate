/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.planner.consumer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.crate.analyze.*;
import io.crate.analyze.repositories.RepositorySettingsModule;
import io.crate.operation.aggregation.impl.AggregationImplModule;
import io.crate.operation.operator.OperatorModule;
import io.crate.operation.predicate.PredicateModule;
import io.crate.operation.scalar.ScalarFunctionModule;
import io.crate.sql.parser.SqlParser;
import io.crate.sql.tree.QualifiedName;
import io.crate.testing.MockedClusterServiceModule;
import io.crate.testing.T3;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import static io.crate.testing.TestingHelpers.isSQL;
import static io.crate.testing.TestingHelpers.newMockedThreadPool;
import static org.junit.Assert.assertThat;

public class ManyTableConsumerTest {

    private Analyzer analyzer;

    @Before
    public void setUp() throws Exception {
        Injector injector = new ModulesBuilder()
                .add(new MockedClusterServiceModule())
                .add(T3.META_DATA_MODULE)
                .add(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ThreadPool.class).toInstance(newMockedThreadPool());
                    }
                })
                .add(new AggregationImplModule())
                .add(new ScalarFunctionModule())
                .add(new PredicateModule())
                .add(new RepositorySettingsModule())
                .add(new OperatorModule())
                .createInjector();
        analyzer = injector.getInstance(Analyzer.class);
    }

    private MultiSourceSelect analyze(String statement) {
        Analysis analysis = analyzer.analyze(SqlParser.createStatement(statement), ParameterContext.EMPTY);
        MultiSourceSelect mss = (MultiSourceSelect) ((SelectAnalyzedStatement) analysis.analyzedStatement()).relation();
        ManyTableConsumer.replaceFieldsWithRelationColumns(mss);
        return mss;
    }

    @Test
    public void testQuerySplitting() throws Exception {
        MultiSourceSelect mss = analyze("select * from t1 " +
                                        "join t2 on t1.a = t2.b " +
                                        "join t3 on t2.b = t3.c " +
                                        "order by t1.a, t2.b, t3.c");
        TwoTableJoin root = ManyTableConsumer.buildTwoTableJoinTree(mss);
        TwoTableJoin t1AndT2 = ((TwoTableJoin) root.left().relation());

        assertThat(t1AndT2.querySpec().where().query(), isSQL("(RELCOL(doc.t1, 0) = RELCOL(doc.t2, 0))"));
        assertThat(root.querySpec().where().query(), isSQL("(RELCOL(join.doc.t1.doc.t2, 3) = RELCOL(doc.t3, 0))"));
    }

    @Test
    public void testQuerySplittingWithRelationReOrdering() throws Exception {
        MultiSourceSelect mss = analyze("select * from t1, t2, t3 " +
                                        "where t3.c = t2.b " +
                                        "order by t3.c");
        TwoTableJoin root = ManyTableConsumer.buildTwoTableJoinTree(mss);
        TwoTableJoin left = (TwoTableJoin) root.left().relation();

        assertThat(left.querySpec().where().query(), isSQL("(RELCOL(doc.t3, 0) = RELCOL(doc.t2, 0))"));
    }

    @Test
    public void testQuerySplittingWithBadRelationOrder() throws Exception {
        MultiSourceSelect mss = analyze("select * from t1 " +
                                        "join t2 on t1.a = t2.b " +
                                        "join t3 on t2.b = t3.c " +
                                        "order by t3.c, t1.a, t2.b");
        TwoTableJoin root = ManyTableConsumer.buildTwoTableJoinTree(mss);
        TwoTableJoin t3AndT1 = ((TwoTableJoin) root.left().relation());

        assertThat(t3AndT1.querySpec().where().query(), isSQL("null"));

        assertThat(root.querySpec().where().query(), Matchers.anyOf(
                // order of the AND clauses is not deterministic, but both are okay as they're semantically the same
                isSQL("((RELCOL(doc.t2, 0) = RELCOL(join.doc.t3.doc.t1, 0)) " +
                      "AND (RELCOL(join.doc.t3.doc.t1, 2) = RELCOL(doc.t2, 0)))"),
                isSQL("((RELCOL(join.doc.t3.doc.t1, 2) = RELCOL(doc.t2, 0)) " +
                      "AND (RELCOL(doc.t2, 0) = RELCOL(join.doc.t3.doc.t1, 0)))")));
    }

    @Test
    public void testOptimizeJoin() throws Exception {
        Set<QualifiedName> pair1 = Sets.newHashSet(T3.T1, T3.T3);
        Set<QualifiedName> pair2 = Sets.newHashSet(T3.T3, T3.T2);
        @SuppressWarnings("unchecked")
        Collection<QualifiedName> qualifiedNames = ManyTableConsumer.orderByJoinConditions(
                Arrays.asList(T3.T1, T3.T2, T3.T3),
                Sets.newHashSet(pair1, pair2),
                ImmutableList.<QualifiedName>of());

        assertThat(qualifiedNames, Matchers.contains(T3.T1, T3.T3, T3.T2));
    }

    @Test
    public void testOptimizeJoinWithoutJoinConditionsAndPreSort() throws Exception {
        Collection<QualifiedName> qualifiedNames = ManyTableConsumer.orderByJoinConditions(
                Arrays.asList(T3.T1, T3.T2, T3.T3),
                ImmutableSet.<Set<QualifiedName>>of(),
                ImmutableList.of(T3.T2));

        assertThat(qualifiedNames, Matchers.contains(T3.T2, T3.T1, T3.T3));
    }
}
