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
package com.systematic.healthcare.fhir.generator;

import ca.uhn.fhir.model.api.annotation.Child;
import ca.uhn.fhir.model.api.annotation.Description;
import ca.uhn.fhir.model.api.annotation.Extension;
import ca.uhn.fhir.model.api.annotation.ResourceDef;
import ca.uhn.fhir.model.dstu2.composite.BoundCodeableConceptDt;
import ca.uhn.fhir.model.dstu2.composite.ElementDefinitionDt;
import ca.uhn.fhir.model.dstu2.composite.ResourceReferenceDt;
import ca.uhn.fhir.model.dstu2.resource.StructureDefinition;
import ca.uhn.fhir.model.primitive.BoundCodeDt;
import ca.uhn.fhir.model.primitive.UriDt;
import ca.uhn.fhir.util.ElementUtil;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import org.apache.commons.lang3.StringUtils;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.Type;
import org.jboss.forge.roaster.model.source.*;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;

import static org.reflections.ReflectionUtils.*;

public class Generator {

    private static final String DSTU2_PACKAGE = "ca.uhn.fhir.model.dstu2";
    private static final String DSTU2_RESOURCE_PACKAGE = DSTU2_PACKAGE + ".resource";
    private static final String DSTU2_COMPOSITE_PACKAGE = DSTU2_PACKAGE + ".composite";
    private static final String DSTU2_PRIMITIVE_PACKAGE = "ca.uhn.fhir.model.primitive";
    public static final String HL7_FHIR_REFERENCE_URL_START = "http://hl7.org/fhir";

    public static JavaClassSource generate(StructureDefinitionProvider resolver) throws Exception {
        return new Generator().convertDefinitionToJavaFile(resolver);
    }

    private JavaClassSource convertDefinitionToJavaFile(StructureDefinitionProvider resolver) throws Exception {
        StructureDefinition def = resolver.getDefinition();
        final JavaClassSource javaClass = Roaster.create(JavaClassSource.class);
        Class<?> superClass = Class.forName(DSTU2_RESOURCE_PACKAGE + "." + def.getConstrainedType());
        javaClass.setPackage(resolver.getOutPackage()).setName(convertNameToValidJavaIdentifier(def.getName())).extendSuperType(superClass);
        addClassResourceDefAnnotation(def, javaClass);
        Set<Field> fields = getAllFields(superClass, withAnnotation(Child.class));
        Map<String, Field> nameToField = new HashMap<>();
        for (Field f : fields) {
            Child annotation = f.getAnnotation(Child.class);
            nameToField.put(annotation.name(), f);
        }

        StructureDefinition.Differential dif = def.getDifferential();
        List<ElementDefinitionDt> elements = dif.getElement();
        for (ElementDefinitionDt element : elements) {
            if (element.getPath().indexOf('.') != -1) {
                //Element name is (path minus the constrained name). E.g. Path for field Subject on Observations is Observation.subject, which we resolve to subject
                String elementName = element.getPath().substring(def.getConstrainedType().length() + 1);
                if (elementName.endsWith("[x]")) {
                    elementName = elementName.substring(0, elementName.indexOf("[x]"));
                }
                if (elementName.equals("extension")) {
                    addExtensionField(javaClass, element, resolver);
                } else {
                    addField(javaClass, nameToField, element, elementName);
                }
            }
        }
        addSliceFields(resolver, javaClass);
        List<FieldSource<JavaClassSource>> allFields = new ArrayList<>();
        allFields.addAll(existingFieldsChanged);
        allFields.addAll(extensionFieldsAdded);
        addIsEmptyMethod(javaClass, allFields);
        addSettersAndGettersForFields(javaClass, existingFieldsChanged, false, superClass);
        addSettersAndGettersForFields(javaClass, extensionFieldsAdded, true, superClass);
        return javaClass;
    }

    private void addSettersAndGettersForFields(JavaClassSource javaClass, List<FieldSource<JavaClassSource>> fieldsAdded, boolean isExtension, Class<?> superClass) {
        for (FieldSource<JavaClassSource> field : fieldsAdded) {
            String fieldName = StringUtils.capitalize(field.getName().substring(2)); // Remove my
            String type = field.getType().getName();
            String genericTypes = Joiner.on(',').join(FluentIterable.from(field.getType().getTypeArguments()).transform(new Function<Type<JavaClassSource>, String>() {
                @Nullable
                @Override
                public String apply(@Nullable Type<JavaClassSource> input) {
                    return input.getName();
                }
            }));
            String getFieldName = fieldName;

            Set<Method> getMethods = getAllMethods(superClass,  withPattern(".*get("+fieldName+"\\(\\)||\"+fieldName+\"Element\\(\\))"));
            for (Method i : getMethods) {
                System.out.println(i.getName());
            }

            String fieldTypeName = field.getType().getName();
            if (fieldTypeName.equals("DateDt") || fieldTypeName.equals("StringDt") || fieldTypeName.equals("BoundCodeDt") || fieldTypeName.equals("BoundCodeableConceptDt")) {
                getFieldName = getFieldName + "Element";
            }
            String bodyGet = "return " + field.getName() + ";";
            if (field.getType().isType(List.class)) {
                bodyGet = "if (" + field.getName() + " == null) {\n" +
                        "   " + field.getName() + " = new java.util.ArrayList<>();\n" +
                        "}\n" +
                        bodyGet;

                type = type + "<" + genericTypes + ">";
            }
            MethodSource<JavaClassSource> methodGet = javaClass.addMethod().setName("get" + getFieldName).setPublic().setReturnType(type).setBody(bodyGet);

            if (!isExtension) {
                // TODO: add support for overrride
                //methodGet.addAnnotation(Override.class);
            }

            String bodySet = field.getName() + " = theValue;\nreturn this;";
            MethodSource<JavaClassSource> methodSet = javaClass.addMethod().setName("set" + fieldName).setPublic()
                    .setReturnType(javaClass.getName()).setBody(bodySet);
            methodSet.addParameter(type, "theValue");
            if (!isExtension) {
                methodSet.addAnnotation(Override.class);
            }
            AnnotationSource<JavaClassSource> childAnnotation = field.getAnnotation(Child.class);
            String min = childAnnotation.getStringValue("min");
            String max = childAnnotation.getStringValue("max");
            if ("0".equals(min) && "0".equals(max)) {
                field.addAnnotation(Deprecated.class);
                methodGet.addAnnotation(Deprecated.class);
                methodSet.addAnnotation(Deprecated.class);
            }
        }
    }

    private void addIsEmptyMethod(JavaClassSource javaClass, List<FieldSource<JavaClassSource>> fieldsAdded) {
        String types = Joiner.on(',').join(FluentIterable.from(fieldsAdded).transform(new FieldSourceGetNameFunction()));
        String body = "return super.isEmpty() && ElementUtil.isEmpty(" + types + ");";
        MethodSource<JavaClassSource> method = javaClass.addMethod().setName("isEmpty").setPublic().setReturnType("boolean").setBody(body);
        method.addAnnotation(Override.class);
        javaClass.addImport(ElementUtil.class);
    }

    private void addSliceFields(StructureDefinitionProvider resolver, JavaClassSource javaClass) {
        for (Map.Entry<String, CompositeValue> i : slicePathToValues.entrySet()) {
            final JavaEnumSource enumClass = Roaster.create(JavaEnumSource.class);
            String enumName = slicedPathToEnumType.get(i.getKey());
            enumClass.setPackage(resolver.getOutPackage()).setName(convertNameToValidJavaIdentifier(enumName) + "Type");
            for (CompositeValueField value : i.getValue().getFields()) {
                enumClass.addEnumConstant().setName(convertNameToValidJavaIdentifier(value.name).toUpperCase());
                // TODO: fix slicing
                //System.out.println(value.fixedCode + " " + value.name + " " + value.type + " " + value.url);
            }
            javaClass.addNestedType(enumClass);
        }
    }

    private String convertNameToValidJavaIdentifier(String enumName) {
        StringBuilder b = new StringBuilder();
        for (String part : enumName.split("[ ]")) {
            b.append(StringUtils.capitalize(part));
        }
        return enumName.replaceAll("[ \\.\\?]", "");
    }

    private Set<String> sliced = new HashSet<>();
    private Map<String, String> slicedPathToEnumType = new HashMap<>();
    private Map<String, CompositeValue> slicePathToValues = new HashMap<>();
    private CompositeValue lastSlicedValue = null;
    private CompositeValueField lastSlicedValueField = null;
    private List<FieldSource<JavaClassSource>> existingFieldsChanged = new ArrayList<>();
    private List<FieldSource<JavaClassSource>> extensionFieldsAdded = new ArrayList<>();

    private void addField(JavaClassSource javaClass, Map<String, Field> elementNameToInheritedField, ElementDefinitionDt element, String elementName) {
        if (!element.getSlicing().getDiscriminator().isEmpty()) {
            sliced.add(element.getPath());
            slicedPathToEnumType.put(element.getPath(), element.getShort());
            lastSlicedValue = new CompositeValue(element.getPath(), element.getSlicing().getDescription());
            slicePathToValues.put(element.getPath(), lastSlicedValue);
        } else {
            // The ElementDefinition is part of the last slice.
            if (lastSlicedValue != null && element.getPath().startsWith(lastSlicedValue.path)) {
                if (element.getPath().equals(lastSlicedValue.path)) {
                    // This defines the name of slice.
                    lastSlicedValueField = new CompositeValueField(element.getName());
                    lastSlicedValue.fields.add(lastSlicedValueField);
                } else if (element.getPath().equals(lastSlicedValue.path + ".code.coding.system")) {
                    lastSlicedValueField.url = ((UriDt) element.getFixed()).getValue();
                } else if (element.getPath().equals(lastSlicedValue.path + ".code.coding.code")) {
                    lastSlicedValueField.fixedCode = String.valueOf(element.getFixed());
                } else if (element.getPath().equals(lastSlicedValue.path + ".value[x]")) {
                    lastSlicedValueField.type = String.valueOf(element.getFixed());
                }
                return;
            } else if (elementName.indexOf('.') != -1) {
                //Dont know why we need theese sub elements. (.code and .code.system etc..)
                return;
            }
        }

        Field inheritedField = elementNameToInheritedField.get(elementName);
        FieldSource<JavaClassSource> field = javaClass.addField().setName("my" + StringUtils.capitalize(elementName)).setPrivate();
        existingFieldsChanged.add(field);
        if (Collection.class.isAssignableFrom(inheritedField.getType())) {
            List<Class<?>> cl = FluentIterable.from(element.getType()).transform(new TypeClassFunction(inheritedField)).toList();
            if (cl.size() == 0) {
                setFieldTypeGeneric(javaClass, inheritedField, field);
            } else {
                String args = Joiner.on(',').join(FluentIterable.from(cl).transform(new ClassToSimpleNameFunction()));
                field.setType(inheritedField.getType().getCanonicalName() + "<" + args + ">");
            }
        } else {
            if (element.getBinding() != null && isStrengthNotExample(element.getBinding()) && element.getBinding().getValueSet() instanceof ResourceReferenceDt) {
                ResourceReferenceDt ref = (ResourceReferenceDt) element.getBinding().getValueSet();
                if (ref.getReference().getValue().startsWith(HL7_FHIR_REFERENCE_URL_START)) {
                    if (BoundCodeableConceptDt.class.isAssignableFrom(inheritedField.getType())) {
                        setFieldTypeGeneric(javaClass, inheritedField, field);
                    } else if (BoundCodeDt.class.isAssignableFrom(inheritedField.getType())) {
                        setFieldTypeGeneric(javaClass, inheritedField, field);
                    } else {
                        field.setType(inheritedField.getType());
                    }
                } else {
                    field.setType(inheritedField.getType());
                }
            } else {
                field.setType(inheritedField.getType());
            }
        }

        List<Class<?>> fieldType = FluentIterable.from(element.getType()).transform(new TypeClassFunction(inheritedField)).toList();
        AnnotationSource<JavaClassSource> childAnnotation = addChildAnnotation(element, elementName, field, false);
        childAnnotation.setClassArrayValue("type", fieldType.toArray(new Class[fieldType.size()]));
        addDescriptionAnnotation(element, field);
    }

    private void setFieldTypeGeneric(JavaClassSource javaClass, Field originalField, FieldSource<JavaClassSource> field) {
        Class<?> typeClass = (Class<?>) ((ParameterizedType) originalField.getGenericType()).getActualTypeArguments()[0];
        field.setType(originalField.getType().getCanonicalName() + "<" + typeClass.getSimpleName() + ">");
        javaClass.addImport(typeClass);
    }

    private boolean isStrengthNotExample(ElementDefinitionDt.Binding binding) {
        return binding.getStrength() == null || !binding.getStrength().equals("example");
    }

    private void addClassResourceDefAnnotation(StructureDefinition def, JavaClassSource javaClass) {
        javaClass.addAnnotation(ResourceDef.class)
                .setStringValue("name", def.getConstrainedType())
                .setStringValue("id", def.getId().getIdPart());
    }

    private void addExtensionField(JavaClassSource javaClass, ElementDefinitionDt element, StructureDefinitionProvider resolver) throws Exception {
        if (element.getType().size() > 1) {
            throw new IllegalStateException("WTF");
        } else {
            if (element.getName() == null) {
                return;
            }
            FieldSource<JavaClassSource> field = javaClass.addField().setName("my" + StringUtils.capitalize(element.getName())).setPrivate();
            extensionFieldsAdded.add(field);
            Class<?> extensionType = getExtensionType(element, resolver);
            if (extensionType != null) {
                field.setType(extensionType);
            } else {
                field.setType(Roaster.parse("public class " + StringUtils.capitalize(element.getName()) + " {}"));
                String errMsg = "Replace " + StringUtils.capitalize(element.getName()) + ".class with correct extension name";
                field.getJavaDoc().addTagValue("TODO:", errMsg);
                field.getJavaDoc().addTagValue("@deprecated", errMsg);
            }
            field.addAnnotation(Extension.class)
                    .setLiteralValue("definedLocally", "false")
                    .setLiteralValue("isModifier", "false")
                    .setStringValue("url", element.getTypeFirstRep().getProfileFirstRep().getValue());
            addChildAnnotation(element, element.getName(), field, true);
            addDescriptionAnnotation(element, field);
        }
    }

    private Class<?> getExtensionType(ElementDefinitionDt element, StructureDefinitionProvider resolver) throws IOException {
        StructureDefinition def = resolver.provideReferenceDefinition(element);
        for (ElementDefinitionDt el : def.getDifferential().getElement()) {
            if (el.getPath().equals("Extension.value[x]")) {
                return getDSTU2ClassType(el.getTypeFirstRep());
            }
        }
        throw new IllegalArgumentException("Could not find name for extension: " + element);
    }

    private AnnotationSource<JavaClassSource> addChildAnnotation(ElementDefinitionDt element, String name, FieldSource<JavaClassSource> field, boolean isExtension) {
        AnnotationSource<JavaClassSource> childAnnotation = field.addAnnotation(Child.class);
        childAnnotation.setStringValue("name", name);
        childAnnotation.setLiteralValue("min", element.getMin().toString());
        childAnnotation.setLiteralValue("max", "*".equals(element.getMax()) ? "Child.MAX_UNLIMITED" : element.getMax());
        childAnnotation.setLiteralValue("order", isExtension ? "Child.ORDER_UNKNOWN" : "Child.REPLACE_PARENT");
        childAnnotation.setLiteralValue("summary", element.getIsSummary() != null ? element.getIsSummary().toString() : "false");
        childAnnotation.setLiteralValue("modifier", element.getIsModifier() != null ? element.getIsModifier().toString() : "false");

        return childAnnotation;
    }

    private void addDescriptionAnnotation(ElementDefinitionDt element, FieldSource<JavaClassSource> field) {
        AnnotationSource<JavaClassSource> descriptionAnnotation = field.addAnnotation(Description.class);
        if (element.getShort() != null) {
            descriptionAnnotation.setStringValue("shortDefinition", element.getShort());
        }
        if (element.getDefinition() != null) {
            descriptionAnnotation.setStringValue("formalDefinition", element.getDefinition());
        }

    }

    private static class TypeClassFunction implements Function<ElementDefinitionDt.Type, Class<?>> {

        private Field field;

        public TypeClassFunction(Field field) {
            this.field = field;
        }

        @Nullable
        @Override
        public Class<?> apply(@Nullable ElementDefinitionDt.Type input) {
            return getClassFromType(input, field);
        }
    }

    private static Class<?> getClassFromType(@Nullable ElementDefinitionDt.Type input, Field originalField) {
        switch (input.getCode()) {
            case "BackboneElement":
                if (originalField.getGenericType() instanceof ParameterizedType) {
                    return (Class<?>) ((ParameterizedType) originalField.getGenericType()).getActualTypeArguments()[0];
                } else {
                    return originalField.getType();
                }
            case "Reference":
                // TODO: for Reference Fields the type should be The referenced type.
                //return originalField.getType();
                return ResourceReferenceDt.class;
            default:
                return getDSTU2ClassType(input);
        }
    }

    private static Class<?> getDSTU2ClassType(@Nullable ElementDefinitionDt.Type input) {
        try {
            try {
                return Class.forName(DSTU2_PRIMITIVE_PACKAGE + "." + StringUtils.capitalize(input.getCode()) + "Dt");
            } catch (ClassNotFoundException ee) {
                return Class.forName(DSTU2_COMPOSITE_PACKAGE + "." + input.getCode() + "Dt");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot locate class", e);
        }
    }

    private static class ClassToSimpleNameFunction implements Function<Class<?>, String> {
        @Nullable
        @Override
        public String apply(@Nullable Class<?> input) {
            return input.getSimpleName();
        }
    }

    private static class CompositeValue {
        public String description;
        public String path;
        public List<CompositeValueField> fields = new ArrayList<>();

        public CompositeValue(String path, String description) {
            this.path = path;
            this.description = description;
        }

        public List<CompositeValueField> getFields() {
            return fields;
        }
    }

    private static class CompositeValueField {
        public String url;
        public String name;
        public String type;
        public String fixedCode;

        public CompositeValueField(String name) {
            this.name = name;
        }
    }

    private static class FieldSourceGetNameFunction implements Function<FieldSource<JavaClassSource>, String> {
        @Nullable
        @Override
        public String apply(@Nullable FieldSource<JavaClassSource> input) {
            return input.getName();
        }
    }
}
