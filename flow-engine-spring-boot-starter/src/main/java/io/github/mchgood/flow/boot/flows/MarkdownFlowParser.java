package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.exception.FlowException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将整份 Markdown 文档按一级标题切分为多个流程段落。
 * <p>仅 ATX 一级标题（单个 # 加空白）开启新段落，标题文本即 flowId，须匹配小驼峰规则；
 * 围栏代码块内的 # 行不参与切分。段落输出采用前缀切片：从文件第 1 行到下一个一级标题之前，
 * 保证核心编译器报错行号与原文件一致。首个标题前的导语被忽略，但导语中不得出现 mermaid
 * 围栏块。本类无状态、线程安全；不解析 Mermaid 图形，图形校验由核心编译器完成。
 * <p>限制：标题去首尾空白后必须匹配 [a-z][A-Za-z0-9]*；同一文件重复标题、段落缺少 mermaid
 * 块均报错；既无标题也无 mermaid 块的文档返回空映射（视为纯说明文档）。
 */
public final class MarkdownFlowParser {
    private static final Pattern FLOW_ID = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#[ \t]+(.+?)[ \t]*#*[ \t]*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})[ \t]*(.*)$");

    /**
     * 按一级标题切分文档。
     *
     * @param sourceName 来源标识，仅用于错误信息定位
     * @param markdown 整份文档原文；null 视为缺失内容
     * @return flowId 到段落 Markdown 的有序映射（保持文件内标题顺序）；无标题时为空映射
     * @throws FlowException INVALID_FLOW_HEADING 标题非法或重复；MERMAID_BLOCK_COUNT
     *         导语含 mermaid 块、文档含 mermaid 块但无标题或段落缺少 mermaid 块；
     *         DEFINITION_LIMIT 文档缺失
     */
    public Map<String, String> split(String sourceName, String markdown) {
        if (markdown == null) {
            throw new FlowException("DEFINITION_LIMIT", "Markdown missing for " + sourceName);
        }
        String[] lines = markdown.replaceFirst("^\\uFEFF", "").split("\n", -1);
        List<Integer> headingLines = new ArrayList<>();
        List<String> flowIds = new ArrayList<>();
        List<Integer> mermaidLines = new ArrayList<>();
        List<Integer> mermaidSections = new ArrayList<>();
        char fenceChar = 0;
        int fenceLength = 0;
        int currentSection = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (fenceLength > 0) {
                if (closesFence(line, fenceChar, fenceLength)) {
                    fenceChar = 0;
                    fenceLength = 0;
                }
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                fenceChar = fence.group(1).charAt(0);
                fenceLength = fence.group(1).length();
                if ("mermaid".equals(fence.group(2).trim())) {
                    mermaidLines.add(i);
                    mermaidSections.add(currentSection);
                }
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String title = heading.group(1).trim();
                if (!FLOW_ID.matcher(title).matches()) {
                    throw new FlowException("INVALID_FLOW_HEADING",
                        "Level-1 heading must match [a-z][A-Za-z0-9]* at " + sourceName + ":" + (i + 1)
                            + " but was \"" + title + "\"");
                }
                if (flowIds.contains(title)) {
                    throw new FlowException("INVALID_FLOW_HEADING",
                        "Duplicate level-1 heading \"" + title + "\" at " + sourceName + ":" + (i + 1));
                }
                headingLines.add(i);
                flowIds.add(title);
                currentSection = headingLines.size() - 1;
            }
        }
        for (int j = 0; j < mermaidLines.size(); j++) {
            if (mermaidSections.get(j) < 0) {
                throw new FlowException("MERMAID_BLOCK_COUNT",
                    "Mermaid block before any level-1 heading at " + sourceName + ":" + (mermaidLines.get(j) + 1));
            }
        }
        if (headingLines.isEmpty()) {
            if (!mermaidLines.isEmpty()) {
                throw new FlowException("MERMAID_BLOCK_COUNT",
                    "Mermaid block without level-1 heading at " + sourceName + ":" + (mermaidLines.get(0) + 1));
            }
            return Map.of();
        }
        for (int k = 0; k < headingLines.size(); k++) {
            if (!mermaidSections.contains(k)) {
                throw new FlowException("MERMAID_BLOCK_COUNT",
                    "Expected one mermaid block under heading \"" + flowIds.get(k) + "\" at "
                        + sourceName + ":" + (headingLines.get(k) + 1));
            }
        }
        Map<String, String> sections = new LinkedHashMap<>();
        List<String> view = Arrays.asList(lines);
        for (int k = 0; k < headingLines.size(); k++) {
            int end = k + 1 < headingLines.size() ? headingLines.get(k + 1) : lines.length;
            sections.put(flowIds.get(k), String.join("\n", view.subList(0, end)));
        }
        return sections;
    }

    private boolean closesFence(String line, char fenceChar, int fenceLength) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        if (spaces > 3 || spaces >= line.length() || line.charAt(spaces) != fenceChar) {
            return false;
        }
        int length = 0;
        int index = spaces;
        while (index < line.length() && line.charAt(index) == fenceChar) {
            length++;
            index++;
        }
        return length >= fenceLength && line.substring(index).isBlank();
    }
}
