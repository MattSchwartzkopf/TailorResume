package com.example.tailorresume;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JobDescriptionCompanyExtractor {

    private static final Pattern COMPANY_LABEL = Pattern.compile(
            "(?im)^\\s*(?:company(?:\\s+name)?|employer|organization(?:\\s+name)?)\\s*[:\\-]\\s*(\\S.*)\\s*$");
    private static final Pattern ABOUT_HEADING = Pattern.compile(
            "(?im)^\\s*#{0,6}\\s*about\\s+([^\\r\\n]+?)\\s*#*\\s*$");
    private static final Pattern CONTEXTUAL_COMPANY = Pattern.compile(
            "\\b(?:At|With|Join|Joining)\\s+(?!the\\b|our\\b)"
                    + "((?:[A-Z][\\p{L}\\p{N}&'.-]*)(?:\\s+[A-Z][\\p{L}\\p{N}&'.-]*){0,3})"
                    + "(?=\\s*,?\\s+(?i:we|our|is|as|to|and|where|today|team|you)\\b)");
    private static final Pattern UNSAFE_FILENAME_CHARACTERS = Pattern.compile("[^A-Za-z0-9]+");
    private static final int MAX_FILENAME_LENGTH = 80;

    private JobDescriptionCompanyExtractor() {
    }

    static String filenameFor(String jobDescription) {
        String company = extractCompanyName(jobDescription);
        if (company == null) {
            return "tailored-resume.pdf";
        }

        String asciiName = Normalizer.normalize(company, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        String safeName = UNSAFE_FILENAME_CHARACTERS.matcher(asciiName).replaceAll("-")
                .replaceAll("^-|-$", "");
        if (safeName.isBlank()) {
            return "tailored-resume.pdf";
        }
        if (safeName.length() > MAX_FILENAME_LENGTH) {
            safeName = safeName.substring(0, MAX_FILENAME_LENGTH).replaceAll("-+$", "");
        }
        return safeName + "-tailored-resume.pdf";
    }

    private static String extractCompanyName(String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return null;
        }

        Matcher labeledCompany = COMPANY_LABEL.matcher(jobDescription);
        if (labeledCompany.find()) {
            return cleanCompanyName(labeledCompany.group(1));
        }

        Matcher aboutHeading = ABOUT_HEADING.matcher(jobDescription);
        while (aboutHeading.find()) {
            String company = cleanCompanyName(aboutHeading.group(1));
            if (!company.equalsIgnoreCase("us")
                    && !company.equalsIgnoreCase("our company")
                    && !company.equalsIgnoreCase("the company")) {
                return company;
            }
        }

        Matcher contextualCompany = CONTEXTUAL_COMPANY.matcher(jobDescription);
        if (contextualCompany.find()) {
            return cleanCompanyName(contextualCompany.group(1));
        }
        return null;
    }

    private static String cleanCompanyName(String company) {
        return company.trim().replaceAll("[\\s|•]+$", "").replaceAll("[.,;:]+$", "").trim();
    }
}
