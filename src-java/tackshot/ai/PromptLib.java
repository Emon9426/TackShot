package tackshot.ai;

/** 内置提示词模板（FR-8.2 提取 / FR-8.3 翻译）。翻译输出带「原/译」行前缀，供结果浮窗对照渲染。 */
final class PromptLib {
    private PromptLib() {}

    static final String FEAT_EXTRACT = "extract";
    static final String FEAT_TRANSLATE = "translate";

    static String extractPrompt() {
        return "你是OCR助手。请逐字转写图片中的全部文字，保留原有的换行与段落结构；"
                + "只输出转写内容，不要解释，不要添加图片中不存在的内容。若图中没有文字，输出「（未检测到文字）」。";
    }

    static String translatePrompt(String target) {
        return "请把图片中的全部文字翻译为" + target + "。输出格式：每个段落输出两行——"
                + "第一行以「原 」开头后接原文；第二行以「译 」开头后接译文；段落之间空一行。"
                + "只输出这些行，不要任何解释。若图中没有可翻译文字，只输出一行「译 （未检测到文字）」。";
    }

    static String featureTitle(String feature, String target) {
        if (FEAT_TRANSLATE.equals(feature)) return "翻译 → " + target;
        return "提取文字";
    }
}
