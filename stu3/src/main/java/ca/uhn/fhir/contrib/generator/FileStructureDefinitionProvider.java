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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.hl7.fhir.dstu3.model.ElementDefinition;
import org.hl7.fhir.dstu3.model.StructureDefinition;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

public class FileStructureDefinitionProvider implements StructureDefinitionProvider {
    private final FhirContext context;
    private final String outPackage;
    private final File structureFile;

    public FileStructureDefinitionProvider(final String outPackage, final File structureFile) {
        this.outPackage = outPackage;
        this.structureFile = structureFile;
        context = FhirContext.forDstu3();
    }

    @Override
	public String getOutPackage() {
        return outPackage;
    }

    @Override
    public StructureDefinition getDefinition() throws IOException {
        final IParser parser = context.newJsonParser();
        return parser.parseResource(StructureDefinition.class, fileToContentString(structureFile));
    }

    @Override
    public StructureDefinition provideReferenceDefinition(final ElementDefinition element) throws IOException {
        final String fileStr = element.getTypeFirstRep().getProfile().substring(element.getTypeFirstRep().getProfile().lastIndexOf('/') + 1) + ".xml";
        final File file = new File(structureFile.getParent(), fileStr);
        if (!file.isFile()) {
            throw new NullPointerException("File not not found: " + file.getAbsolutePath());
        }
        final IParser parser = context.newXmlParser();
        return parser.parseResource(StructureDefinition.class, fileToContentString(file));
    }

    private String fileToContentString(final File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }
}
