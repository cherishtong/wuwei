package com.wuwei.resume;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Quick util to create a poi-tl-compatible resume .docx template
 * with placeholders matching the resume-builder data schema.
 *
 * Run: java TemplateGenerator.java
 * Output: ~/.wuwei/skills/resume-builder/templates/通用简历模板.docx
 */
public class TemplateGenerator {

    // Minimal valid .docx — just XML files in a ZIP
    private static final String CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
        </Types>""";

    private static final String RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
            Target="word/document.xml"/>
        </Relationships>""";

    private static final String WORD_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>""";

    public static void main(String[] args) throws Exception {
        String documentXml = buildDocumentXml();
        String home = System.getProperty("user.home");
        Path outDir = Paths.get(home, ".wuwei", "skills", "resume-builder", "templates");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("通用简历模板.docx");

        try (var fos = new FileOutputStream(outFile.toFile());
             var zos = new ZipOutputStream(fos)) {

            addEntry(zos, "[Content_Types].xml", CONTENT_TYPES);
            addEntry(zos, "_rels/.rels", RELS);
            addEntry(zos, "word/_rels/document.xml.rels", WORD_RELS);
            addEntry(zos, "word/document.xml", documentXml);
        }

        System.out.println("Template created: " + outFile);
    }

    private static void addEntry(ZipOutputStream zos, String path, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String buildDocumentXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <w:body>
            %s
              </w:body>
            </w:document>""".formatted(String.join("\n",
                // ── Title ──
                p("个人简历", 28, true),
                p("", 10),

                // ── Basic Info ──
                p("【基本信息】", 14, true),
                row("姓名", "{{name}}", "性别", "{{gender}}"),
                row("出生日期", "{{birth}}", "手机", "{{phone}}"),
                row("邮箱", "{{email}}", "工作年限", "{{experience}}"),
                row("毕业院校", "{{school}}", "专业", "{{major}}"),
                row("公司", "{{company}}", "部门", "{{dept}}"),
                row("职位", "{{role}}", "", ""),
                p("", 10),

                // ── Professional Summary ──
                p("【个人总结】", 14, true),
                p("{{summary}}", 10),
                p("", 10),

                // ── Skills ──
                p("【专业技能】", 14, true),
                p("{{skillText}}", 10),
                p("", 10),

                // ── Work Experience ──
                p("【工作经历】", 14, true),
                poiLoop("experiences",
                    p("{{=#this.idx}}. {{company}} | {{role}} | {{start}} ~ {{end}}", 10),
                    p("    {{dept}}", 9)
                ),
                p("", 10),

                // ── Projects ──
                p("【项目经验】", 14, true),
                poiLoop("projects",
                    p("{{=#this.idx}}. {{name}}（{{time}}）", 10, true),
                    p("   角色：{{role}}", 9),
                    p("   描述：{{desc}}", 9)
                ),
                p("", 10)

            ));
    }

    private static String p(String text, int fontSize) { return p(text, fontSize, false); }

    private static String p(String text, int fontSize, boolean bold) {
        // Convert half-width fontSize to twips (1pt = 20 twips)
        int sz = fontSize * 20;
        String boldXml = bold ? "<w:b/><w:bCs/>" : "";
        return """
            <w:p>
              <w:pPr><w:jc w:val="left"/></w:pPr>
              <w:r><w:rPr><w:rFonts w:ascii="Microsoft YaHei" w:hAnsi="Microsoft YaHei" w:eastAsia="Microsoft YaHei"/>
            %s<w:sz w:val="%d"/></w:rPr><w:t xml:space="preserve">%s</w:t></w:r>
            </w:p>""".formatted(boldXml, sz, escape(text));
    }

    private static String row(String label1, String value1, String label2, String value2) {
        int sz = 20; // 10pt
        StringBuilder sb = new StringBuilder();
        sb.append("<w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>");
        sb.append(cell(label1, sz, true));
        sb.append(cell(value1, sz, false));
        if (!label2.isEmpty() || !value2.isEmpty()) {
            sb.append(cell(label2, sz, true));
            sb.append(cell(value2, sz, false));
        }
        sb.append("</w:p>");
        return sb.toString();
    }

    private static String cell(String text, int sz, boolean isLabel) {
        String labelStyle = isLabel ? "<w:b/>" : "";
        return "<w:r><w:rPr><w:rFonts w:ascii=\"Microsoft YaHei\" w:hAnsi=\"Microsoft YaHei\" w:eastAsia=\"Microsoft YaHei\"/>%s<w:sz w:val=\"%d\"/></w:rPr><w:t xml:space=\"preserve\">%s    </w:t></w:r>"
            .formatted(labelStyle, sz, escape(text));
    }

    private static String poiLoop(String arrayName, String... items) {
        return "{{#" + arrayName + "}}\n" + String.join("\n", items) + "\n{{/" + arrayName + "}}";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                   .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
