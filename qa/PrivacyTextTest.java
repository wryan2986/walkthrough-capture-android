package local.walkthrough;

import java.util.List;

public class PrivacyTextTest {
    private static int checks;

    private static void expect(PrivacyTextRules rules, boolean expected, String... samples) {
        for (String sample : samples) {
            checks++;
            if (rules.matches(sample) != expected) {
                throw new AssertionError("Privacy text check " + checks + " failed");
            }
        }
    }

    public static void main(String[] args) {
        PrivacyTextRules off = new PrivacyTextRules(false, false, List.of("  "));
        expect(off, false, "QA@example.org", "+1 (212) 555-0101", "", null);
        PrivacyTextRules email = new PrivacyTextRules(true, false, List.of());
        expect(email, true, "Email QA+demo@example.co.uk.", "ＱＡ＠ＥＸＡＭＰＬＥ．ＯＲＧ", "élise@example.fr");
        expect(email, false, "name@localhost", "name @ example.org", "www.example.com", "212-555-0101",
            "a".repeat(42000) + " @missing", "a@b." + "z");
        PrivacyTextRules phone = new PrivacyTextRules(false, true, List.of());
        expect(phone, true, "+1 (212) 555-0101", "mobile 212.555.0101.", "555-0188", "+44 20 7946 0999",
            "numbers 212-555-0101 212-555-0188", "１２３４５６７", "phone +33 1 23 45 67 89 ext 24");
        expect(phone, false, "2026-11-20", "11-20-2026", "123456", "1234567890123456789", "example@abc.org");
        PrivacyTextRules terms = new PrivacyTextRules(false, false, List.of(" A[2] ", "CAFÉ", "Station 7"));
        expect(terms, true, "Tag a[2] is present", "Cafe\u0301", "ＳＴＡＴＩＯＮ ７ north");
        expect(terms, false, "A2", "CAFE", "Station 8", null);
        System.out.println(checks + " deterministic privacy matching checks passed.");
    }
}
