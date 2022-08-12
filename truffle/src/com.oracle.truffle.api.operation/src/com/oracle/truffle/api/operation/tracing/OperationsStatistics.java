/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.operation.tracing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.tools.utils.json.JSONArray;
import com.oracle.truffle.tools.utils.json.JSONObject;
import com.oracle.truffle.tools.utils.json.JSONTokener;

// per-context singleton
public class OperationsStatistics {
    private final Map<Class<?>, GlobalOperationStatistics> statisticsMap = new HashMap<>();
    static final ThreadLocal<OperationsStatistics> STATISTICS = new ThreadLocal<>();
    private Path statePath;
    private FileChannel stateFile;
    private FileLock fileLock;

    OperationsStatistics(String statePath) {
        this.statePath = Path.of(statePath);
        read();
    }

    public static OperationsStatistics create(String statePath) {
        return new OperationsStatistics(statePath);
    }

    @TruffleBoundary
    public OperationsStatistics enter() {
        OperationsStatistics prev = STATISTICS.get();
        STATISTICS.set(this);
        return prev;
    }

    @TruffleBoundary
    public void exit(OperationsStatistics prev) {
        STATISTICS.set(prev);
    }

    private void read() {
        try {
            stateFile = FileChannel.open(statePath, StandardOpenOption.READ, StandardOpenOption.WRITE);
            fileLock = stateFile.lock();

            JSONTokener tok = new JSONTokener(Channels.newInputStream(stateFile));
            if (!tok.more()) {
                return;
            }

            JSONArray o = new JSONArray(tok);

            for (int i = 0; i < o.length(); i++) {
                JSONObject data = o.getJSONObject(i);
                GlobalOperationStatistics value = GlobalOperationStatistics.deserialize(this, data);
                this.statisticsMap.put(value.opsClass, value);
            }

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void write() {
        try {
            try {
                JSONArray result = new JSONArray();
                this.statisticsMap.forEach((k, v) -> {
                    result.put(v.serialize());
                });

                stateFile.position(0);
                stateFile.write(ByteBuffer.wrap(result.toString().getBytes(StandardCharsets.UTF_8)));
                stateFile.truncate(stateFile.position());

                fileLock.release();
            } finally {
                stateFile.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.statisticsMap.forEach((k, v) -> {
            try {
                v.writeDecisions();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    synchronized GlobalOperationStatistics getStatsistics(Class<?> opsClass) {
        return statisticsMap.computeIfAbsent(opsClass, (c) -> new GlobalOperationStatistics(opsClass));
    }

    // per-context per-ops
    static final class GlobalOperationStatistics {
        private final List<EnabledExecutionTracer> allTracers = new ArrayList<>();
        private final ThreadLocal<EnabledExecutionTracer> currentTracer = new ThreadLocal<>();

        private Class<?> opsClass;
        private String decisionsFile;
        private String[] instrNames;
        private String[][] specNames;

        GlobalOperationStatistics(Class<?> opsClass) {
            this.opsClass = opsClass;
        }

        public void writeDecisions() throws IOException {
            setNames();
            EnabledExecutionTracer tracer = collect();
            try (FileChannel ch = FileChannel.open(Path.of(decisionsFile), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                JSONArray result = tracer.serializeDecisions(this);
                ch.truncate(0);
                ch.write(ByteBuffer.wrap(result.toString(4).getBytes(StandardCharsets.UTF_8)));
            }
        }

        private static GlobalOperationStatistics deserialize(OperationsStatistics parent, JSONObject data) throws ClassNotFoundException {
            Class<?> key = Class.forName(data.getString("key"));
            GlobalOperationStatistics result = parent.getStatsistics(key);
            result.allTracers.add(EnabledExecutionTracer.deserialize(data));
            return result;
        }

        private EnabledExecutionTracer collect() {
            EnabledExecutionTracer tracer = new EnabledExecutionTracer();
            for (EnabledExecutionTracer other : allTracers) {
                tracer.merge(other);
            }

            return tracer;
        }

        private JSONObject serialize() {
            JSONObject result = collect().serialize();
            result.put("key", opsClass.getName());
            return result;
        }

        private void setNames() {
            if (this.decisionsFile != null) {
                return;
            }

            this.decisionsFile = ExecutionTracer.DECISIONS_FILE_MAP.get(opsClass);
            this.instrNames = ExecutionTracer.INSTR_NAMES_MAP.get(opsClass);
            this.specNames = ExecutionTracer.SPECIALIZATION_NAMES_MAP.get(opsClass);
        }

        public EnabledExecutionTracer getTracer() {
            if (currentTracer.get() == null) {
                return createTracer();
            } else {
                return currentTracer.get();
            }
        }

        private EnabledExecutionTracer createTracer() {
            assert currentTracer.get() == null;
            EnabledExecutionTracer tracer = new EnabledExecutionTracer();
            currentTracer.set(tracer);
            allTracers.add(tracer);
            return tracer;
        }

    }

    static class DisabledExecutionTracer extends ExecutionTracer {
        static final DisabledExecutionTracer INSTANCE = new DisabledExecutionTracer();

        @Override
        public void startFunction(Node node) {
        }

        @Override
        public void endFunction(Node node) {
        }

        @Override
        public void traceInstruction(int bci, int id) {
        }

        @Override
        public void traceActiveSpecializations(int bci, int id, boolean[] activeSpecializations) {
        }

        @Override
        public void traceSpecialization(int bci, int id, int specializationId, Object... values) {
        }
    }

    private static class EnabledExecutionTracer extends ExecutionTracer {

        private static class SpecializationKey {
            final int instructionId;
            @CompilationFinal(dimensions = 1) final boolean[] activeSpecializations;
            int countActive = -1;

            SpecializationKey(int instructionId, boolean[] activeSpecializations) {
                this.instructionId = instructionId;
                this.activeSpecializations = activeSpecializations;
            }

            @Override
            public int hashCode() {
                final int prime = 31;
                int result = 1;
                result = prime * result + Arrays.hashCode(activeSpecializations);
                result = prime * result + Objects.hash(instructionId);
                return result;
            }

            public int getCountActive() {
                if (countActive == -1) {
                    int c = 0;
                    for (int i = 0; i < activeSpecializations.length; i++) {
                        if (activeSpecializations[i]) {
                            c++;
                        }
                    }
                    countActive = c;
                }

                return countActive;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (obj == null) {
                    return false;
                }
                if (getClass() != obj.getClass()) {
                    return false;
                }
                SpecializationKey other = (SpecializationKey) obj;
                return Arrays.equals(activeSpecializations, other.activeSpecializations) && instructionId == other.instructionId;
            }

            private JSONObject serialize() {
                JSONObject result = new JSONObject();
                result.put("i", instructionId);
                result.put("n", activeSpecializations.length);

                JSONArray activeSpecsData = new JSONArray();
                for (int i = 0; i < activeSpecializations.length; i++) {
                    if (activeSpecializations[i]) {
                        activeSpecsData.put(i);
                    }
                }

                result.put("a", activeSpecsData);

                return result;
            }

            private static SpecializationKey deserialize(JSONObject obj) {
                int id = obj.getInt("i");
                int count = obj.getInt("n");
                boolean[] activeSpecializations = new boolean[count];
                JSONArray activeSpecsData = obj.getJSONArray("a");
                for (int i = 0; i < activeSpecsData.length(); i++) {
                    activeSpecializations[activeSpecsData.getInt(i)] = true;
                }

                return new SpecializationKey(id, activeSpecializations);
            }

            public JSONObject serializeDecision(GlobalOperationStatistics stats) {
                JSONObject result = new JSONObject();
                result.put("type", "Quicken");
                String instrName = stats.instrNames[instructionId];
                String shortName;
                if (instrName.startsWith("c.")) {
                    shortName = instrName.substring(2);
                } else {
                    assert instrName.startsWith("sc.");
                    shortName = instrName.substring(3);
                }
                result.put("operation", shortName);

                JSONArray specsData = new JSONArray();
                result.put("specializations", specsData);
                for (int i = 0; i < activeSpecializations.length; i++) {
                    if (activeSpecializations[i]) {
                        specsData.put(stats.specNames[instructionId][i]);
                    }
                }

                return result;
            }

            @Override
            public String toString() {
                return "SpecializationKey [" + instructionId + ", " + Arrays.toString(activeSpecializations) + "]";
            }
        }

        private Map<SpecializationKey, Long> activeSpecializationsMap = new HashMap<>();

        @Override
        public void startFunction(Node node) {
        }

        @Override
        public void endFunction(Node node) {
        }

        @Override
        public void traceInstruction(int bci, int id) {
        }

        @Override
        public void traceActiveSpecializations(int bci, int id, boolean[] activeSpecializations) {
// boolean anyTrue = false;
// for (int i = 0; i < activeSpecializations.length; i++) {
// anyTrue |= activeSpecializations[i];
// }
// if (!anyTrue) {
// return;
// }

            SpecializationKey key = new SpecializationKey(id, activeSpecializations);
            Long l = activeSpecializationsMap.get(key);
            if (l == null) {
                activeSpecializationsMap.put(key, 1L);
            } else if (l != Long.MAX_VALUE) {
                activeSpecializationsMap.put(key, l + 1);
            }
        }

        @Override
        public void traceSpecialization(int bci, int id, int specializationId, Object... values) {
        }

        private void merge(EnabledExecutionTracer other) {
            other.activeSpecializationsMap.forEach((k, v) -> {
                Long existing = this.activeSpecializationsMap.get(k);
                if (existing == null) {
                    this.activeSpecializationsMap.put(k, v);
                } else if (Long.MAX_VALUE - existing > v) {
                    this.activeSpecializationsMap.put(k, existing + v);
                } else {
                    this.activeSpecializationsMap.put(k, Long.MAX_VALUE);
                }
            });
        }

        private JSONObject serialize() {
            JSONObject result = new JSONObject();

            JSONArray activeSpecializationsData = new JSONArray();

            activeSpecializationsMap.forEach((k, v) -> {
                JSONObject activeSpecData = k.serialize();
                activeSpecData.put("c", v);
                activeSpecializationsData.put(activeSpecData);
            });

            result.put("as", activeSpecializationsData);

            return result;
        }

        private static EnabledExecutionTracer deserialize(JSONObject obj) {
            EnabledExecutionTracer inst = new EnabledExecutionTracer();
            JSONArray activeSpecializationsData = obj.getJSONArray("as");

            for (int i = 0; i < activeSpecializationsData.length(); i++) {
                JSONObject activeSpecData = activeSpecializationsData.getJSONObject(i);
                long count = activeSpecData.getLong("c");
                SpecializationKey key = SpecializationKey.deserialize(activeSpecData);
                inst.activeSpecializationsMap.put(key, count);
            }

            return inst;
        }

        public JSONArray serializeDecisions(GlobalOperationStatistics stats) {
            JSONArray result = new JSONArray();
            result.put("This file is autogenerated by the Operations DSL.");
            result.put("Do not modify, as it will be overwritten when running with tracing support.");
            result.put("Use the overrides file to alter the optimisation decisions.");
            int numDecisions = 100;
            activeSpecializationsMap.entrySet().stream().filter(e -> e.getKey().getCountActive() > 0).sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(numDecisions).forEachOrdered(
                            e -> {
                                result.put(e.getKey().serializeDecision(stats));
                            });
            return result;
        }

        @Override
        public String toString() {
            return "EnabledExecutionTracer [" + activeSpecializationsMap + "]";
        }

    }

}
