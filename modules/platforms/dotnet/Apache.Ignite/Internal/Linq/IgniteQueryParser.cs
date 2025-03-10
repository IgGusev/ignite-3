﻿/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace Apache.Ignite.Internal.Linq;

using System.Threading;
using Dml;
using Remotion.Linq.Parsing.ExpressionVisitors.Transformation;
using Remotion.Linq.Parsing.ExpressionVisitors.TreeEvaluation;
using Remotion.Linq.Parsing.Structure;
using Remotion.Linq.Parsing.Structure.ExpressionTreeProcessors;
using Remotion.Linq.Parsing.Structure.NodeTypeProviders;

/// <summary>
/// Query parser.
/// </summary>
internal static class IgniteQueryParser
{
    /** */
    private static readonly ThreadLocal<QueryParser> ThreadLocalInstance = new(CreateParser);

    /// <summary>
    /// Gets the default instance for current thread.
    /// </summary>
    public static QueryParser Instance => ThreadLocalInstance.Value!;

    /// <summary>
    /// Creates the parser.
    /// </summary>
    private static QueryParser CreateParser()
    {
        var transformerRegistry = ExpressionTransformerRegistry.CreateDefault();

        var proc = CreateCompoundProcessor(transformerRegistry);

        var parser = new ExpressionTreeParser(CreateNodeTypeProvider(), proc);

        return new QueryParser(parser);
    }

    /// <summary>
    /// Creates the node type provider.
    /// </summary>
    private static CompoundNodeTypeProvider CreateNodeTypeProvider()
    {
        var methodInfoRegistry = MethodInfoBasedNodeTypeRegistry.CreateFromRelinqAssembly();

        methodInfoRegistry.Register(ExecuteDeleteExpressionNode.MethodInfos, typeof(ExecuteDeleteExpressionNode));
        methodInfoRegistry.Register(ExecuteUpdateExpressionNode.MethodInfos, typeof(ExecuteUpdateExpressionNode));

        return new CompoundNodeTypeProvider(new INodeTypeProvider[]
        {
            methodInfoRegistry,
            MethodNameBasedNodeTypeRegistry.CreateFromRelinqAssembly()
        });
    }

    /// <summary>
    /// Creates CompoundExpressionTreeProcessor.
    /// </summary>
    private static CompoundExpressionTreeProcessor CreateCompoundProcessor(
        IExpressionTranformationProvider transformationProvider)
    {
        return new CompoundExpressionTreeProcessor(
            new IExpressionTreeProcessor[]
            {
                new PartialEvaluatingExpressionTreeProcessor(new NullEvaluatableExpressionFilter()),
                new TransformingExpressionTreeProcessor(transformationProvider)
            });
    }

    /// <summary>
    /// Empty implementation of IEvaluatableExpressionFilter.
    /// </summary>
    private sealed class NullEvaluatableExpressionFilter : EvaluatableExpressionFilterBase
    {
        // No-op.
    }
}
