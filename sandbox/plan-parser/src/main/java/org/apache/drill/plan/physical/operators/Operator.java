/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.drill.plan.physical.operators;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.drill.plan.ast.Arg;
import org.apache.drill.plan.ast.Op;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements a function for an operator on a single line of the physical plan.
 * <p/>
 * The life cycle of an operator is
 * <nl>
 * <li>The operator's constructor is defined using Operator.defineOperator</li>
 * <li>The operator is constructed via Operator.create.  It is expected that
 * the operator will fill in references to it's own outputs into the DAG bindings</li>
 * <li>The operator is linked by a call to its link() method.  At this point, the
 * operator can look at its arguments and resolve references to its inputs.
 * This is when it should add itself as a data listener and when it should request
 * any schema that it needs from upstream Operator's.</li>
 * <li>The operator's run() method is called.  Most operators should simply return at this
 * point, but data sources should start calling emit with data records.</li>
 * <li>The operator will be notified of incoming data.  It should process this data
 * and emit the result.</li>
 * </nl>
 */
public abstract class Operator {
    private static AtomicInteger genCount = new AtomicInteger(0);
    private static final Map<String, Class<? extends Operator>> operatorMap = Maps.newHashMap();

    public Operator(Op op, Map<Integer, Operator> bindings, int inputArgs, int outputArgs) {
        checkArity(op, inputArgs, outputArgs);
        for (Arg arg : op.getOutputs()) {
            bindings.put(arg.asSymbol().getInt(), this);
        }
    }

    // only for testing and constants
    protected Operator() {
    }

    static {
        ArithmeticOp.define();
        Bind.define();
        Filter.define();
        ScanJson.define();
        Explode.define();
        Implode.define();
    }


    public static void defineOperator(String name, Class<? extends Operator> clazz) {
        if (operatorMap.containsKey(name)) {
            throw new RuntimeException(String.format("Duplicate operator name for %s vs %s", clazz, operatorMap.get(name)));
        }
        operatorMap.put(name, clazz);
    }

    public static Operator create(Op op, Map<Integer, Operator> bindings) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {
        Class<? extends Operator> c = operatorMap.get(op.getOp());
        if (c == null) {
            throw new IllegalArgumentException(String.format("No such operator as %s", op.getOp()));
        }

        Constructor<? extends Operator> con = c.getConstructor(Op.class, Map.class);
        return con.newInstance(op, bindings);
    }


    public static String gensym() {
        return String.format("__sym-%d", genCount.incrementAndGet());
    }

    private final List<DataListener> dataOut = Lists.newArrayList();
    private List<BatchListener> batchOut = Lists.newArrayList();

    public void addDataListener(DataListener listener) {
        this.dataOut.add(listener);
    }

    public void addBatchListener(BatchListener listener) {
        this.batchOut.add(listener);
    }

    protected void emit(Object r) {
        for (DataListener listener : dataOut) {
            listener.notify(r);
        }
    }

    protected void finishBatch(Object parent) {
        for (BatchListener listener : batchOut) {
            listener.endBatch(parent);
        }
    }

    public double evalAsDouble() {
        throw new UnsupportedOperationException("default no can do");  //To change body of created methods use File | Settings | File Templates.
    }

    public Object eval() {
        return null;
    }

    public abstract void link(Op op, Map<Integer, Operator> bindings);

    public void close() {
        // do nothing by default... over-ride for clever behavior
    }

    public abstract Schema getSchema();

    protected void checkArity(Op op, int inputArgs, int outputArgs) {
        List<Arg> in = op.getInputs();
        if (in.size() != inputArgs) {
            throw new IllegalArgumentException("bind should have exactly two arguments (an expression and a data source)");
        }

        List<Arg> out = op.getOutputs();
        if (out.size() != outputArgs) {
            throw new IllegalArgumentException(String.format("Operator should have exactly %d outputs, not %d", outputArgs, out.size()));
        }
    }
}
