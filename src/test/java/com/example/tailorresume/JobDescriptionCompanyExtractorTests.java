package com.example.tailorresume;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobDescriptionCompanyExtractorTests {

    @Test
    void usesCompanyNameFromExplicitLabel() {
        assertEquals("Acme-Inc-tailored-resume.pdf",
                JobDescriptionCompanyExtractor.filenameFor("Role: Engineer\nCompany: Acme, Inc."));
    }

    @Test
    void usesCompanyNameFromAboutHeading() {
        assertEquals("North-Star-tailored-resume.pdf",
                JobDescriptionCompanyExtractor.filenameFor("Senior Engineer\n\nAbout North Star"));
    }

    @Test
    void identifiesCompanyFromNaturalJobDescriptionPhrasing() {
        assertEquals("Microsoft-tailored-resume.pdf",
                JobDescriptionCompanyExtractor.filenameFor(
                        "We are looking for an engineer. At Microsoft, we build products used worldwide."));
        assertEquals("Acme-Group-tailored-resume.pdf",
                JobDescriptionCompanyExtractor.filenameFor(
                        "Join Acme Group as a senior engineer and shape our platform."));
    }

    @Test
    void defaultsWhenCompanyIsNotIdentified() {
        assertEquals("tailored-resume.pdf",
                JobDescriptionCompanyExtractor.filenameFor("Seeking an experienced engineer."));
        assertEquals("tailored-resume.pdf",
                JobDescriptionCompanyExtractor.filenameFor("About us\nGreat opportunity."));
        assertEquals("tailored-resume.pdf",
                JobDescriptionCompanyExtractor.filenameFor("At the end of the day, we value good work."));
    }

    @Test
    void removesUnsafeFilenameCharacters() {
        assertEquals("Acme-Group-tailored-resume.pdf",
                JobDescriptionCompanyExtractor.filenameFor("Company: ../Acme & Group/"));
    }
}
