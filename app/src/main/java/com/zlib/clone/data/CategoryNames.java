package com.zlib.clone.data;

import com.zlib.clone.model.Category;

import java.util.HashMap;
import java.util.Map;

/**
 * 网站分类名 → 中文展示名。
 *
 * 设计约束（见 README「分类数据源」）：
 * - 只做展示名映射，不合并分类、不改写来源分类 ID；一级/二级分类树原样保留；
 * - 键使用「父分类 ID + 原英文名」，避免不同一级分类下的同名二级分类（如 Historical）互相覆盖；
 * - 没有映射的分类保留网站原名（displayName = sourceName），不猜测、不根据书名关键词分类。
 */
public final class CategoryNames {

    private static final Map<String, String> ROOT_NAMES = new HashMap<String, String>();
    private static final Map<String, String> CHILD_NAMES = new HashMap<String, String>();

    static {
        ROOT_NAMES.put("Arts", "艺术");
        ROOT_NAMES.put("Astronomy", "天文学");
        ROOT_NAMES.put("Biography & Autobiography", "传记与回忆录");
        ROOT_NAMES.put("Biology and other natural sciences", "生物学与自然科学");
        ROOT_NAMES.put("Business & Economics", "经济与管理");
        ROOT_NAMES.put("Chemistry", "化学");
        ROOT_NAMES.put("Children's Books", "童书");
        ROOT_NAMES.put("Comics & Graphic Novels", "漫画与图像小说");
        ROOT_NAMES.put("Computers", "计算机");
        ROOT_NAMES.put("Cookbooks, Food & Wine", "烹饪与美食");
        ROOT_NAMES.put("Crime, Thrillers & Mystery", "犯罪·惊悚·悬疑");
        ROOT_NAMES.put("Earth Sciences", "地球科学");
        ROOT_NAMES.put("Education Studies & Teaching", "教育与教学");
        ROOT_NAMES.put("Engineering", "工程技术");
        ROOT_NAMES.put("Erotica", "情色文学");
        ROOT_NAMES.put("Fantasy", "奇幻");
        ROOT_NAMES.put("Fiction", "小说");
        ROOT_NAMES.put("History", "历史");
        ROOT_NAMES.put("Housekeeping & Leisure", "家政与休闲");
        ROOT_NAMES.put("Jurisprudence & Law", "法律");
        ROOT_NAMES.put("Languages", "语言");
        ROOT_NAMES.put("Linguistics", "语言学");
        ROOT_NAMES.put("Mathematics", "数学");
        ROOT_NAMES.put("Medicine", "医学");
        ROOT_NAMES.put("Nature, Animals & Pets", "自然·动物·宠物");
        ROOT_NAMES.put("Others", "其他");
        ROOT_NAMES.put("Physics", "物理学");
        ROOT_NAMES.put("Poetry", "诗歌");
        ROOT_NAMES.put("Psychology", "心理学");
        ROOT_NAMES.put("Reference", "工具书");
        ROOT_NAMES.put("Religion & Spirituality", "宗教与灵性");
        ROOT_NAMES.put("Romance", "言情");
        ROOT_NAMES.put("Science (General)", "科学总论");
        ROOT_NAMES.put("Science Fiction", "科幻");
        ROOT_NAMES.put("Self-Help, Relationships & Lifestyle", "心理与成长");
        ROOT_NAMES.put("Society, Politics & Philosophy", "哲学与社科");
        ROOT_NAMES.put("Sports, Hobbies & Games", "体育·爱好·游戏");
        ROOT_NAMES.put("Technique", "技术");
        ROOT_NAMES.put("Travel", "旅行");

        CHILD_NAMES.put("14/Adventure Stories & Action", "冒险与动作");
        CHILD_NAMES.put("14/American Fiction", "美国小说");
        CHILD_NAMES.put("14/Anthologies", "小说选集");
        CHILD_NAMES.put("14/Classics", "文学经典");
        CHILD_NAMES.put("14/Contemporary Fiction", "当代小说");
        CHILD_NAMES.put("14/Drama", "戏剧");
        CHILD_NAMES.put("14/Essays", "随笔");
        CHILD_NAMES.put("14/Family Sagas", "家族史诗");
        CHILD_NAMES.put("14/Historical", "历史小说");
        CHILD_NAMES.put("14/Horror", "恐怖");
        CHILD_NAMES.put("14/Humour", "幽默");
        CHILD_NAMES.put("14/Literary Fiction", "纯文学");
        CHILD_NAMES.put("14/Metaphysical & Visionary", "玄秘与幻想");
        CHILD_NAMES.put("14/Myths & Folklore", "神话与民间传说");
        CHILD_NAMES.put("14/Nautical & Maritime Fiction", "航海小说");
        CHILD_NAMES.put("14/Novel", "长篇小说");
        CHILD_NAMES.put("14/Others", "其他");
        CHILD_NAMES.put("14/Peoples & Cultures - Fiction", "民族与文化小说");
        CHILD_NAMES.put("14/Psychological", "心理小说");
        CHILD_NAMES.put("14/Religious & Inspirational", "宗教与励志小说");
        CHILD_NAMES.put("14/Short Stories", "短篇小说");
        CHILD_NAMES.put("14/War & Military Fiction", "战争与军事小说");
        CHILD_NAMES.put("14/Westerns", "西部小说");
        CHILD_NAMES.put("14/Women's Fiction", "女性小说");
        CHILD_NAMES.put("14/World Fiction", "世界小说");
        CHILD_NAMES.put("14/Young Adult", "青少年小说");
        CHILD_NAMES.put("17/African History", "非洲史");
        CHILD_NAMES.put("17/American History", "美国史");
        CHILD_NAMES.put("17/American Studies", "美国研究");
        CHILD_NAMES.put("17/Ancient History", "古代史");
        CHILD_NAMES.put("17/Archaeology", "考古学");
        CHILD_NAMES.put("17/Asian History", "亚洲史");
        CHILD_NAMES.put("17/Australian & Oceanian History", "澳洲与大洋洲史");
        CHILD_NAMES.put("17/Canadian History", "加拿大史");
        CHILD_NAMES.put("17/Chinese History", "中国史");
        CHILD_NAMES.put("17/Christian History", "基督教史");
        CHILD_NAMES.put("17/European History", "欧洲史");
        CHILD_NAMES.put("17/Historiography", "史学史");
        CHILD_NAMES.put("17/Indian History", "印度史");
        CHILD_NAMES.put("17/Islamic History", "伊斯兰史");
        CHILD_NAMES.put("17/Latin American History", "拉丁美洲史");
        CHILD_NAMES.put("17/Middle Ages", "中世纪");
        CHILD_NAMES.put("17/Middle Eastern History", "中东史");
        CHILD_NAMES.put("17/Military History", "军事史");
        CHILD_NAMES.put("17/Modern History", "近代史");
        CHILD_NAMES.put("17/Religious History", "宗教史");
        CHILD_NAMES.put("17/Russian & Soviet History", "俄罗斯与苏联史");
        CHILD_NAMES.put("17/World History", "世界史");
        CHILD_NAMES.put("3/Artists, Architects & Photographers", "艺术家·建筑师·摄影师");
        CHILD_NAMES.put("3/Business & Finance", "商业与金融人物");
        CHILD_NAMES.put("3/Education Biography", "教育人物");
        CHILD_NAMES.put("3/Entertainment Biography", "娱乐人物");
        CHILD_NAMES.put("3/Essays, Journals & Letters", "随笔·日记与书信");
        CHILD_NAMES.put("3/Film, Television & Music", "影视与音乐人物");
        CHILD_NAMES.put("3/General & Miscellaneous Biography", "综合传记");
        CHILD_NAMES.put("3/Historical", "历史人物传记");
        CHILD_NAMES.put("3/Holocaust", "大屠杀");
        CHILD_NAMES.put("3/Literary Biography", "文学家传记");
        CHILD_NAMES.put("3/Medical, Legal & Social Sciences", "医学·法律与社会科学人物");
        CHILD_NAMES.put("3/Memoir", "回忆录");
        CHILD_NAMES.put("3/Novelists, Poets & Playwrights", "小说家·诗人·剧作家");
        CHILD_NAMES.put("3/Peoples & Cultures - Biography", "民族与文化传记");
        CHILD_NAMES.put("3/Political", "政治人物");
        CHILD_NAMES.put("3/Reference", "传记工具书");
        CHILD_NAMES.put("3/Religious", "宗教人物");
        CHILD_NAMES.put("3/Science, Mathematics & Technology", "科学·数学与技术人物");
        CHILD_NAMES.put("3/Social & Health Issues", "社会与健康议题");
        CHILD_NAMES.put("3/Sports & Adventure Biography", "体育与冒险传记");
        CHILD_NAMES.put("3/Theatre & Performance Art", "戏剧与表演艺术人物");
        CHILD_NAMES.put("3/Tragic Life Stories", "悲剧人生");
        CHILD_NAMES.put("3/True Crime", "真实犯罪");
        CHILD_NAMES.put("3/War & Espionage", "战争与间谍");
    }

    private CategoryNames() {
    }

    /** 给分类补上 displayName（不修改其他字段）。 */
    public static void apply(Category category) {
        if (category == null) {
            return;
        }
        String mapped = null;
        if (category.isRoot()) {
            mapped = ROOT_NAMES.get(category.sourceName);
        } else {
            mapped = CHILD_NAMES.get(category.parentId + "/" + category.sourceName);
        }
        category.displayName = (mapped == null || mapped.length() == 0)
                ? category.sourceName : mapped;
    }

    /** 供界面选择语言筛选时使用的中文名（网站语言值 → 展示名）。 */
    public static String languageDisplayName(String sourceValue) {
        if (sourceValue == null || sourceValue.length() == 0) {
            return "全部语言";
        }
        String value = sourceValue.trim().toLowerCase(java.util.Locale.US);
        if ("chinese".equals(value)) {
            return "简体中文";
        }
        if ("traditional chinese".equals(value)) {
            return "繁体中文";
        }
        if ("english".equals(value)) {
            return "英语";
        }
        if ("japanese".equals(value)) {
            return "日语";
        }
        if ("russian".equals(value)) {
            return "俄语";
        }
        if ("french".equals(value)) {
            return "法语";
        }
        if ("german".equals(value)) {
            return "德语";
        }
        if ("spanish".equals(value)) {
            return "西班牙语";
        }
        return sourceValue;
    }
}
