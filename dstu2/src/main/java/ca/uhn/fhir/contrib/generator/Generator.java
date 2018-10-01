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

import ca.uhn.fhir.model.api.IDatatype;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.annotation.Child;
import ca.uhn.fhir.model.api.annotation.Description;
import ca.uhn.fhir.model.api.annotation.Extension;
import ca.uhn.fhir.model.api.annotation.ResourceDef;
import ca.uhn.fhir.model.primitive.BoundCodeDt;
import ca.uhn.fhir.util.ElementUtil;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.dstu3.model.ElementDefinition;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.StructureDefinition;
import org.hl7.fhir.dstu3.model.UriType;
import org.hl7.fhir.exceptions.FHIRException;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.source.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;

public class Generator {

    private static final String STU3_PACKAGE = "org.hl7.fhir.dstu3.model";
    private static final String STU3_RESOURCE_PACKAGE = STU3_PACKAGE + "";
    private static final String STU3_COMPOSITE_PACKAGE = STU3_PACKAGE + ".composite";

    public static final String HL7_FHIR_REFERENCE_URL_START = "http://hl7.org/fhir";

    public static JavaClassSource generate(final StructureDefinitionProvider resolver) throws Exception {
        return new Generator().convertDefinitionToJavaFile(resolver);
    }

    private JavaClassSource convertDefinitionToJavaFile(final StructureDefinitionProvider resolver) throws Exception {
        final StructureDefinition def = resolver.getDefinition();
        final JavaClassSource javaClass = Roaster.create(JavaClassSource.class);
        final Class<? extends IResource> superClass = (Class<? extends IResource>) Class.forName(STU3_RESOURCE_PACKAGE + "." + def.getType());
        javaClass.setPackage(resolver.getOutPackage()).setName(convertNameToValidJavaIdentifier(def.getName())).extendSuperType(superClass);
        addClassResourceDefAnnotation(def, javaClass);
        final Map<String, ResourceParser.FieldInfo> fieldInfo = new ResourceParser().parseResource(superClass);

        final StructureDefinition.StructureDefinitionDifferentialComponent dif = def.getDifferential();
        final List<ElementDefinition> elements = dif.getElement();
        for (final ElementDefinition element : elements) {
            if (element.getPath().indexOf('.') != -1) {
                //Element name is (path minus the constrained name). E.g. Path for field Subject on Observations is Observation.subject, which we resolve to subject
                String elementName = element.getPath().substring(def.getType().length() + 1);
                if (elementName.endsWith("[x]")) {
                    elementName = elementName.substring(0, elementName.indexOf("[x]"));
                }
                if (elementName.equals("extension")) {
                    addExtensionField(javaClass, element, resolver);
                } else {
                    addField(javaClass, fieldInfo, element, elementName);
                }
            }
        }
        addSliceFields(resolver, javaClass);
        final List<FieldSource<JavaClassSource>> allFields = new ArrayList<>();
        allFields.addAll(existingFieldsChanged);
        allFields.addAll(extensionFieldsAdded);
        addIsEmptyMethod(javaClass, allFields);
        addSettersAndGettersForFields(javaClass, existingFieldsChanged, false, superClass, fieldInfo);
        addSettersAndGettersForFields(javaClass, extensionFieldsAdded, true, superClass, fieldInfo);
        return javaClass;
    }

    private void addSettersAndGettersForFields(final JavaClassSource javaClass, final List<FieldSource<JavaClassSource>> fieldsAdded, final boolean isExtension, final Class<?> superClass, final Map<String, ResourceParser.FieldInfo> fieldInfo) {
        for (final FieldSource<JavaClassSource> field : fieldsAdded) {
            final String fieldName = StringUtils.capitalize(field.getName().substring(2)); // Remove my
            final ResourceParser.FieldInfo existingField = fieldInfo.get(fieldName.toLowerCase());
            String genericType = null;
            if (field.getType().getTypeArguments().size() == 1) {
                genericType = field.getType().getTypeArguments().get(0).getName();
            } else if (field.getType().getTypeArguments().size() > 1) {
                genericType = IDatatype.class.getName();
            }
            String type = field.getType().getName();
            String bodyGetSimple = "return " + field.getName() + ";";
            if (field.getType().isType(List.class)) {
                bodyGetSimple = "if (" + field.getName() + " == null) {\n" +
                        "   " + field.getName() + " = new java.util.ArrayList<>();\n" +
                        "}\n" +
                        bodyGetSimple;

                type = type + "<" + genericType + ">";
            }
            final AnnotationSource<JavaClassSource> childAnnotation = field.getAnnotation(Child.class);
            final String min = childAnnotation.getStringValue("min");
            final String max = childAnnotation.getStringValue("max");
            final boolean deprecate = "0".equals(min) && "0".equals(max);
            if (existingField != null) {
                for (final Method method : existingField.getMethods()) {
                    final String simpleType = genericType != null ? genericType : field.getType().getName();
                    if (method.getName().startsWith("get") && method.getName().endsWith("FirstRep")) {
                        final String body = "if (get" + StringUtils.capitalize(existingField.getOrigFieldName()) + "().isEmpty()) {\n" +
                                "    return add" + StringUtils.capitalize(existingField.getOrigFieldName()) + "();\n" +
                                "}\n" +
                                "return get" + StringUtils.capitalize(existingField.getOrigFieldName()) + "().get(0);";
                        addGetMethod(javaClass, method.getName(), simpleType, body, deprecate);
                    } else if (method.getName().startsWith("get") && method.getName().endsWith("Element")) {
                        final String body = "if (" + field.getName() + " == null) {\n" +
                                "     " + field.getName() + " = new " + type + "();\n" +
                                "    \n" +
                                "return " + field.getName() + ";}";
                        addGetMethod(javaClass, method.getName(), type, body, deprecate);
                    } else if (method.getName().startsWith("get")) {
                        addGetMethod(javaClass, method.getName(), type, bodyGetSimple, deprecate);
                    } else if (method.getName().startsWith("set")) {
                        final String bodySet = field.getName() + " = theValue;\nreturn this;";
                        final MethodSource<JavaClassSource> methodSet = javaClass.addMethod().setName("set" + fieldName).setPublic()
                                .setReturnType(javaClass.getName()).setBody(bodySet);
                        methodSet.addParameter(type, "theValue");
                        methodSet.addAnnotation(Override.class);
                        if (deprecate) {
                            methodSet.addAnnotation(Deprecated.class);
                        }
                    } else if (method.getName().startsWith("add") && method.getParameterTypes().length == 0) {
                        final String body = simpleType + " newType = new " + simpleType + "();\n" +
                                "    get" + StringUtils.capitalize(existingField.getOrigFieldName()) + "().add(newType);\n" +
                                "return newType;";
                        addGetMethod(javaClass, method.getName(), simpleType, body, deprecate);
                    }
                }
                continue;
            }
            //Extensions and slicing
        }
    }

    private void addGetMethod(final JavaClassSource javaClass, final String methodName, final String type, final String body, final boolean deprecate) {
        final MethodSource<JavaClassSource> method = javaClass.addMethod().setName(methodName).setPublic().setReturnType(type).setBody(body);
        if (deprecate) {
            method.addAnnotation(Deprecated.class);
        }
    }

    private void addIsEmptyMethod(final JavaClassSource javaClass, final List<FieldSource<JavaClassSource>> fieldsAdded) {
        final String types = Joiner.on(',').join(FluentIterable.from(fieldsAdded).transform(new FieldSourceGetNameFunction()));
        final String body = "return super.isEmpty() && ElementUtil.isEmpty(" + types + ");";
        final MethodSource<JavaClassSource> method = javaClass.addMethod().setName("isEmpty").setPublic().setReturnType("boolean").setBody(body);
        method.addAnnotation(Override.class);
        javaClass.addImport(ElementUtil.class);
    }

    private void addSliceFields(final StructureDefinitionProvider resolver, final JavaClassSource javaClass) {
        for (final Map.Entry<String, CompositeValue> i : slicePathToValues.entrySet()) {
            final JavaEnumSource enumClass = Roaster.create(JavaEnumSource.class);
            final String enumName = slicedPathToEnumType.get(i.getKey());
            enumClass.setPackage(resolver.getOutPackage()).setName(convertNameToValidJavaIdentifier(enumName) + "Type");
            for (final CompositeValueField value : i.getValue().getFields()) {
                enumClass.addEnumConstant().setName(convertNameToValidJavaIdentifier(value.name).toUpperCase());
                // TODO: fix slicing
                //System.out.println(value.fixedCode + " " + value.name + " " + value.type + " " + value.url);
            }
            javaClass.addNestedType(enumClass);
        }
    }

    private String convertNameToValidJavaIdentifier(final String enumName) {
        final StringBuilder b = new StringBuilder();
        if (enumName.contains(" "))
            for (final String part : enumName.split("[ ]"))
                b.append(StringUtils.capitalize(part));
        else
            b.append(StringUtils.capitalize(enumName));
        return b.toString().replaceAll("[ \\.\\?]", "");
    }

    private final Set<String> sliced = new HashSet<>();
    private final Map<String, String> slicedPathToEnumType = new HashMap<>();
    private final Map<String, CompositeValue> slicePathToValues = new HashMap<>();
    private CompositeValue lastSlicedValue = null;
    private CompositeValueField lastSlicedValueField = null;
    private final List<FieldSource<JavaClassSource>> existingFieldsChanged = new ArrayList<>();
    private final List<FieldSource<JavaClassSource>> extensionFieldsAdded = new ArrayList<>();

    private void addField(final JavaClassSource javaClass, final Map<String, ResourceParser.FieldInfo> fieldInfo, final ElementDefinition element, final String elementName) {
        if (!element.getSlicing().getDiscriminator().isEmpty()) {
            sliced.add(element.getPath());
            slicedPathToEnumType.put(element.getPath(), element.getShort() != null ? element.getShort() : element.getId());
            lastSlicedValue = new CompositeValue(element.getPath(), element.getSlicing().getDescription());
            slicePathToValues.put(element.getPath(), lastSlicedValue);
        } else {
            // The ElementDefinition is part of the last slice.
            if (lastSlicedValue != null && element.getPath().startsWith(lastSlicedValue.path)) {
                if (element.getPath().equals(lastSlicedValue.path)) {
                    // This defines the name of slice.
                    lastSlicedValueField = new CompositeValueField(element.getSliceName());
                    lastSlicedValue.fields.add(lastSlicedValueField);
                } else if (element.getPath().equals(lastSlicedValue.path + ".code.coding.system")) {
                    lastSlicedValueField.url = ((UriType) element.getFixed()).getValue();
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

        ResourceParser.FieldInfo inheritedField = fieldInfo.get(elementName.toLowerCase());

        //Sliced values
        if (inheritedField == null) {
            inheritedField = fieldInfo.get(elementName.split("\\.")[0]);
            return;

        }

        final FieldSource<JavaClassSource> field = javaClass.addField().setName("my" + StringUtils.capitalize(elementName)).setPrivate();
        existingFieldsChanged.add(field);
        if (Collection.class.isAssignableFrom(inheritedField.getType())) {
            final List<Class<?>> cl = FluentIterable.from(element.getType()).transform(new TypeClassFunction(inheritedField)).toList();
            if (cl.size() == 0) {
                setFieldTypeGeneric(javaClass, inheritedField, field);
            } else {
                final String args = Joiner.on(',').join(FluentIterable.from(cl).transform(new ClassToSimpleNameFunction()));
                field.setType(inheritedField.getType().getCanonicalName() + "<" + args + ">");
            }
        } else {
            if (element.getBinding() != null && isBindingStrengthNotExample(element.getBinding()) && element.getBinding().getValueSet() instanceof Reference) {
                final Reference ref = (Reference) element.getBinding().getValueSet();
                if (ref.getReference().startsWith(HL7_FHIR_REFERENCE_URL_START)) {
//                    if (BoundCodeableConcept.class.isAssignableFrom(inheritedField.getType())) {
//                        setFieldTypeGeneric(javaClass, inheritedField, field);
//                    } else 
                    if (BoundCodeDt.class.isAssignableFrom(inheritedField.getType())) {
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

        final List<Class<?>> fieldType = FluentIterable.from(element.getType()).transform(new TypeClassFunction(inheritedField)).toList();
        final AnnotationSource<JavaClassSource> childAnnotation = addFieldChildAnnotation(element, elementName, field, false);
        childAnnotation.setClassArrayValue("type", fieldType.toArray(new Class[fieldType.size()]));
        addFieldDescriptionAnnotation(element, field);
    }

    private void setFieldTypeGeneric(final JavaClassSource javaClass, final ResourceParser.FieldInfo originalField, final FieldSource<JavaClassSource> field) {
        final Class<?> typeClass = (Class<?>) ((ParameterizedType) originalField.getGenericType()).getActualTypeArguments()[0];
        field.setType(originalField.getType().getCanonicalName() + "<" + typeClass.getSimpleName() + ">");
        javaClass.addImport(typeClass);
    }

    private boolean isBindingStrengthNotExample(final ElementDefinition.ElementDefinitionBindingComponent binding) {
        return binding.getStrength() == null || !binding.getStrength().equals("example");
    }

    private void addClassResourceDefAnnotation(final StructureDefinition def, final JavaClassSource javaClass) {
        javaClass.addAnnotation(ResourceDef.class)
                .setStringValue("name", def.getType())
                .setStringValue("id", def.getId());
    }

    private void addExtensionField(final JavaClassSource javaClass, final ElementDefinition element, final StructureDefinitionProvider resolver) throws Exception {
        if (element.getType().size() > 1) {
            throw new IllegalStateException("WTF");
        } else {

            //unnamed slicing is currently not handled
//        		if(!element.getSlicing().isEmpty())

            if (element.getSliceName() == null) {
                return;
            }
            final FieldSource<JavaClassSource> field = javaClass.addField().setName(element.getSliceName()).setPrivate();
            extensionFieldsAdded.add(field);
            final Class<?> extensionType = getExtensionType(element, resolver);
            if (extensionType != null) {
                field.setType(extensionType);
            } else {
                field.setType(Roaster.parse("public class " + StringUtils.capitalize(element.getSliceName()) + " {}"));
                final String errMsg = "Replace " + StringUtils.capitalize(element.getSliceName()) + ".class with correct extension name";
                addTodoAndDeprecationAnnotation(field, errMsg);
            }
            addFieldExtensionAnnotation(element, field);
            addFieldChildAnnotation(element, element.getSliceName(), field, true);
            addFieldDescriptionAnnotation(element, field);
        }
    }

    private void addFieldExtensionAnnotation(final ElementDefinition element, final FieldSource<JavaClassSource> field) {
        field.addAnnotation(Extension.class)
                .setLiteralValue("definedLocally", "false")
                .setLiteralValue("isModifier", "false")
                .setStringValue("url", element.getTypeFirstRep().getProfile());
    }

    private void addTodoAndDeprecationAnnotation(final FieldSource<JavaClassSource> field, final String errMsg) {
        field.getJavaDoc().addTagValue("TODO:", errMsg);
        field.getJavaDoc().addTagValue("@deprecated", errMsg);
    }

    private Class<?> getExtensionType(final ElementDefinition element, final StructureDefinitionProvider resolver) throws IOException, FHIRException {
        final StructureDefinition def = resolver.provideReferenceDefinition(element);
        for (final ElementDefinition el : def.getDifferential().getElement()) {
//        	final int uppercardinality = el.getMaxValueIntegerType() != null ? el.getMaxValueIntegerType().getValue() : 0;
//        	System.out.println(uppercardinality);
            if (el.getPath().startsWith("Extension.value") && !el.getPath().startsWith("Extension.value[x]")) {
                return getSTU3ClassType(el.getTypeFirstRep());
            }
            //Sub extensions
            else if (el.getPath().startsWith("Extension.extension") && el.getPath().contains("value")) {
                return getSTU3ClassType(el.getTypeFirstRep());
            }
        }
        throw new IllegalArgumentException("Could not find extension type(s) for : " + element);
    }

    private AnnotationSource<JavaClassSource> addFieldChildAnnotation(final ElementDefinition element, final String name, final FieldSource<JavaClassSource> field, final boolean isExtension) {
        final AnnotationSource<JavaClassSource> childAnnotation = field.addAnnotation(Child.class);
        childAnnotation.setStringValue("name", name);
        childAnnotation.setLiteralValue("min", element.getMin() + "");
        childAnnotation.setLiteralValue("max", "*".equals(element.getMax()) ? "Child.MAX_UNLIMITED" : Strings.isNullOrEmpty(element.getMax()) ? "" : element.getMax());
        childAnnotation.setLiteralValue("order", isExtension ? "Child.ORDER_UNKNOWN" : "Child.REPLACE_PARENT");
        childAnnotation.setLiteralValue("summary", element.getIsSummaryElement() != null ? "true" : "false");
        childAnnotation.setLiteralValue("modifier", element.getIsModifierElement() != null ? "true" : "false");

        return childAnnotation;
    }

    private void addFieldDescriptionAnnotation(final ElementDefinition element, final FieldSource<JavaClassSource> field) {
        final AnnotationSource<JavaClassSource> descriptionAnnotation = field.addAnnotation(Description.class);
        if (element.getShort() != null) {
            descriptionAnnotation.setStringValue("shortDefinition", element.getShort());
        }
        if (element.getDefinition() != null) {
            descriptionAnnotation.setStringValue("formalDefinition", element.getDefinition());
        }

    }

    private static class TypeClassFunction implements Function<ElementDefinition.TypeRefComponent, Class<?>> {

        private final ResourceParser.FieldInfo field;

        public TypeClassFunction(final ResourceParser.FieldInfo field) {
            this.field = field;
        }

        @Nullable
        @Override
        public Class<?> apply(@Nullable final ElementDefinition.TypeRefComponent input) {
            return getClassFromType(input, field);
        }
    }

    private static Class<?> getClassFromType(@Nullable final ElementDefinition.TypeRefComponent input, final ResourceParser.FieldInfo originalField) {
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
                return Reference.class;
            default:
                return getSTU3ClassType(input);
        }
    }

    private static Class<?> getSTU3ClassType(@Nullable final ElementDefinition.TypeRefComponent input) {
        try {
            try {
                return Class.forName(STU3_RESOURCE_PACKAGE + "." + StringUtils.capitalize(input.getCode()));
            } catch (final ClassNotFoundException ee) {
                return Class.forName(STU3_RESOURCE_PACKAGE + "." + input.getCode());
            }
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException("Cannot locate class", e);
        }
    }

    private static class ClassToSimpleNameFunction implements Function<Class<?>, String> {
        @Nullable
        @Override
        public String apply(@Nullable final Class<?> input) {
            return input.getSimpleName();
        }
    }

    private static class CompositeValue {
        public String description;
        public String path;
        public List<CompositeValueField> fields = new ArrayList<>();

        public CompositeValue(final String path, final String description) {
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

        public CompositeValueField(final String name) {
            this.name = name;
        }
    }

    private static class FieldSourceGetNameFunction implements Function<FieldSource<JavaClassSource>, String> {
        @Nullable
        @Override
        public String apply(@Nullable final FieldSource<JavaClassSource> input) {
            return input.getName();
        }
    }
}
