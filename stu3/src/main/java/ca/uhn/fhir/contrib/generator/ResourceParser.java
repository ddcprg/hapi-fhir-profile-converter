/*
 * Copyright (C) 2015 Systematic A/S
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uhn.fhir.contrib.generator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.instance.model.api.IBaseResource;

import ca.uhn.fhir.model.api.BaseIdentifiableElement;
import ca.uhn.fhir.model.api.annotation.Child;

public class ResourceParser {

    public static final String FIRST_REP = "FirstRep";
    public static final String ELEMENT = "Element";

    public static void main(final String[] args) {
        for (final Map.Entry<String, FieldInfo> i : new ResourceParser().parseResource(Patient.class).entrySet()) {
            System.out.println(i.getKey() + " " + i.getValue());
        }

    }

    public Map<String, FieldInfo> parseElement(final Class<? extends BaseIdentifiableElement> element) {
        final Stack<Class<?>> stack = parseStack(element);
        return parseAnnotatedFields(stack);
    }

    public Map<String, FieldInfo> parseResource(final Class<? extends IBaseResource> resource) {
        final Stack<Class<?>> stack = parseStack(resource);
        return parseAnnotatedFields(stack);
    }

    private Stack<Class<?>> parseStack(final Class<?> resource) {
        final Stack<Class<?>> hierarchy = new Stack<>();
        Class<?> clazz = resource;
        do {
            hierarchy.push(clazz);
            clazz = clazz.getSuperclass();
        } while (!clazz.isAssignableFrom(Object.class));
        return hierarchy;
    }

    private Map<String, FieldInfo> parseAnnotatedFields(final Stack<Class<?>> stack) {
        final Map<String, FieldInfo> fieldNameToFieldInfo = new HashMap<>();
        while (!stack.isEmpty()) {
            final Class<?> cls = stack.pop();
            for (final Field field : cls.getDeclaredFields()) {
                final Child child = field.getAnnotation(Child.class);
                if (child != null) {
                    final FieldInfo fi = new FieldInfo(field.getName(), field);
                    final String fieldName = fi.getLowercaseName();
                    if (fieldNameToFieldInfo.containsKey(fieldName)) {
                        final FieldInfo fiOld = fieldNameToFieldInfo.get(fieldName);
                        fi.setParent(fiOld);
                    }
                    fieldNameToFieldInfo.put(fieldName, fi);
                }
            }

            for (final Method i : cls.getMethods()) {
                String name = i.getName();
                if (name.startsWith("get") || name.startsWith("set") || name.startsWith("add")) {
                    name = name.substring(3);
                } else {
                    continue;//Method not a get set or add.
                }
                if (name.endsWith(FIRST_REP)) {
                    name = name.substring(0, name.length() - FIRST_REP.length());
                }
                if (name.endsWith(ELEMENT)) {
                    name = name.substring(0, name.length() - ELEMENT.length());
                }
                if (fieldNameToFieldInfo.containsKey(name.toLowerCase())) {
                    fieldNameToFieldInfo.get(name.toLowerCase()).addMethod(i);

                }

            }
        }
        return fieldNameToFieldInfo;
    }

    public class FieldInfo {

        private final Child child;
        private FieldInfo parent;
        private final String lowercaseName;
        private final String origFieldName;
        private final Field field;
        private final List<Method> methods = new ArrayList<>();

        public FieldInfo(final String nameArg, final Field field) {
//            if (!nameArg.startsWith("my")) {
//                throw new IllegalArgumentException("FHIR fields should start with my, was: " + nameArg);
//            }
            this.origFieldName = nameArg;//nameArg.substring(2);
            this.lowercaseName = origFieldName.toLowerCase();
            this.child = field.getAnnotation(Child.class);
            if (!lowercaseName.equalsIgnoreCase(child.name())) {
                throw new IllegalArgumentException("Name does no equal child lowercaseName " + lowercaseName + " " + child.name());
            }

            this.field = field;
        }

        public void setParent(final FieldInfo parent) {
            this.parent = parent;
        }

        public Field getField() {
            return field;
        }

        public String getOrigFieldName() {
            return origFieldName;
        }

        public FieldInfo getParent() {
            return parent;
        }

        public String getLowercaseName() {
            return lowercaseName;
        }

        public void addMethod(final Method method) {
            methods.add(method);
        }

        @Override
        public String toString() {
            return "FieldInfo{" +
                    "parent=" + parent +
                    ", lowercaseName='" + lowercaseName + '\'' +
                    ", field=" + field +
                    ", methods=" + methods +
                    '}';
        }

        public Class<?> getType() {
            return field.getType();
        }

        public Object getGenericType() {
            return field.getGenericType();
        }

        public List<Method> getMethods() {
            return methods;
        }
    }
}
