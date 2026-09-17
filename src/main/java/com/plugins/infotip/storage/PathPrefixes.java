package com.plugins.infotip.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路径前缀的抽离与还原：纯算法那一半
 *
 * <p>
 * 只跟字符串打交道，不碰 PSI、不碰 IDE，好单独验。改 XML 的那一半在
 * {@link PathPrefixEditor}。
 * </p>
 *
 * @author yc556&claude-fable-5
 * @version 1.0
 */
public final class PathPrefixes {

    /**
     * 一个前缀至少要被这么多条规则用上才值得声明出来
     */
    private static final int MIN_COVERAGE = 2;

    /**
     * {@code prefix="id" } 这一串本身要占的字符数，用来估收益
     */
    private static final int PREFIX_ATTR_OVERHEAD = 10;

    /**
     * {@code <prefix id="" path=""/>} 这一行的固定开销
     */
    private static final int PREFIX_DECL_OVERHEAD = 25;

    /**
     * 和父目录重复的头部至少这么长才值得掐掉，太短了掐着没意义
     */
    private static final int MIN_SHARED_HEAD = 3;

    /**
     * 掐头之后 id 至少要留这么多字符，否则宁可用全名
     */
    private static final int MIN_ID_LENGTH = 3;

    private PathPrefixes() {
    }

    /**
     * 按路径段边界裁掉前缀
     *
     * <p>
     * <b>必须卡在 {@code /} 上</b>，不能用裸的 {@code startsWith}：真实数据里
     * {@code /a/CarrierRecruit} 和 {@code /a/CarrierRecruitReg} 就是字符串前缀关系，
     * 裸比会把后者的条目全归到前者名下，路径当场错掉。
     * </p>
     *
     * @param fullPath 完整路径
     * @param prefix   前缀，不以 {@code /} 结尾
     * @return 剩下那截（以 {@code /} 开头）；正好等于前缀本身时返回空串；不在前缀下面返回 {@code null}
     */
    public static String stripPrefix(String fullPath, String prefix) {
        if (null == fullPath || null == prefix || prefix.isEmpty()) {
            return null;
        }
        if (fullPath.equals(prefix)) {
            return "";
        }
        if (fullPath.length() > prefix.length()
                && fullPath.startsWith(prefix)
                && '/' == fullPath.charAt(prefix.length())) {
            return fullPath.substring(prefix.length());
        }
        return null;
    }

    /**
     * 挑出值得抽离的前缀，并给每条路径指派一个
     *
     * <p>
     * 做法是贪心：候选前缀 = 每条路径的各级祖先目录连它自己，按长度<b>从长到短</b>过一遍，
     * 够本的就留下、把它覆盖的路径认领走。长的先挑才省得最多，剩下的再交给短前缀。
     * </p>
     * <p>
     * 够本的判据是净收益为正：{@code 条数 × (前缀长 - id长 - 每条的属性开销) - 声明这一行的开销}。
     * 这个式子自动把 {@code /src} 这种短前缀滤掉——省下的 4 个字符还不够写 {@code prefix="src" }。
     * </p>
     *
     * @param paths 全部规则的完整路径，允许含 null 和空串，会被跳过
     * @return 指派结果，路径顺序和入参一致
     */
    public static Plan plan(List<String> paths) {
        final Map<String, Integer> coverage = new HashMap<>();
        for (String path : paths) {
            for (String candidate : candidates(path)) {
                coverage.merge(candidate, 1, Integer::sum);
            }
        }
        //长的排前面，长度一样按字典序，保证同样的输入每次得到同样的结果
        final List<String> ordered = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : coverage.entrySet()) {
            if (entry.getValue() >= MIN_COVERAGE) {
                ordered.add(entry.getKey());
            }
        }
        ordered.sort((a, b) -> a.length() != b.length() ? b.length() - a.length() : a.compareTo(b));

        final Map<String, String> chosen = new LinkedHashMap<>();
        final String[] owner = new String[paths.size()];
        for (String candidate : ordered) {
            final List<Integer> hits = new ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                if (null == owner[i] && null != stripPrefix(paths.get(i), candidate)) {
                    hits.add(i);
                }
            }
            if (hits.size() < MIN_COVERAGE) {
                continue;
            }
            final String id = newId(candidate, chosen.keySet());
            if (gain(candidate, id, hits.size()) <= 0) {
                continue;
            }
            chosen.put(id, candidate);
            for (int index : hits) {
                owner[index] = id;
            }
        }
        return new Plan(chosen, owner);
    }

    /**
     * 净收益：正数才值得抽
     */
    private static int gain(String prefix, String id, int count) {
        final int savedPerRule = prefix.length() - id.length() - PREFIX_ATTR_OVERHEAD;
        return count * savedPerRule - (prefix.length() + id.length() + PREFIX_DECL_OVERHEAD);
    }

    /**
     * 一条路径能用的全部候选前缀：它自己，以及各级祖先目录
     *
     * <p>
     * 要带上自己——目录规则本身常常就是一批文件规则的前缀，
     * 用户数据里 {@code /src/pages/carrierManagement/CarrierRecruit} 既是一条规则，
     * 也是它下面四条的前缀。
     * </p>
     */
    private static List<String> candidates(String path) {
        final List<String> result = new ArrayList<>();
        if (null == path) {
            return result;
        }
        final String trimmed = trimTrailingSlash(path);
        //只写 / 或者空的规则没有前缀可言
        if (trimmed.length() < 2 || '/' != trimmed.charAt(0)) {
            return result;
        }
        result.add(trimmed);
        for (int i = trimmed.lastIndexOf('/'); i > 0; i = trimmed.lastIndexOf('/', i - 1)) {
            result.add(trimmed.substring(0, i));
        }
        return result;
    }

    /**
     * 给前缀起个 id：取最后一段，掐掉和父目录重复的那截头部，首字母小写
     *
     * <p>
     * 掐头不是为了好看，是为了<b>真能抽出来</b>：id 每条规则都要写一遍，它越长收益越低。
     * {@code CarrierRecruit} 挂在 {@code carrierManagement} 底下，两边都以 {@code carrier}
     * 开头，留 {@code recruit} 一样认得出来，却让这批 4 条规则从净亏 3 个字符变成净省 32 个。
     * </p>
     * <p>
     * 掐完太短就不掐（{@code abc} 和 {@code abcd} 那种），宁可 id 长一点也不要留个认不出的残根。
     * 重名时加数字。
     * </p>
     */
    private static String newId(String prefix, java.util.Set<String> used) {
        final int lastSlash = prefix.lastIndexOf('/');
        String last = prefix.substring(lastSlash + 1);
        if (lastSlash > 0) {
            final String parent = prefix.substring(prefix.lastIndexOf('/', lastSlash - 1) + 1, lastSlash);
            final int shared = sharedHeadLength(last, parent);
            if (shared >= MIN_SHARED_HEAD && last.length() - shared >= MIN_ID_LENGTH) {
                last = last.substring(shared);
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < last.length(); i++) {
            final char c = last.charAt(i);
            //id 要能当属性值直接写，只留下最保险的那几类字符
            if (Character.isLetterOrDigit(c) || '_' == c || '-' == c) {
                sb.append(0 == sb.length() ? Character.toLowerCase(c) : c);
            }
        }
        final String base = 0 == sb.length() ? "p" : sb.toString();
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            final String next = base + i;
            if (!used.contains(next)) {
                return next;
            }
        }
    }

    /**
     * 两个目录名开头重复了多少个字符，不分大小写
     */
    private static int sharedHeadLength(String a, String b) {
        final int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && Character.toLowerCase(a.charAt(i)) == Character.toLowerCase(b.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * 去掉路径末尾多写的 {@code /}，和 {@code TreesUtils} 的归一化保持一致
     */
    private static String trimTrailingSlash(String path) {
        String result = path.trim();
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 抽离方案
     */
    public static final class Plan {
        /**
         * 选中的前缀：id 到完整路径，写进 {@code <prefixes>} 的顺序就是这个顺序
         */
        private final Map<String, String> prefixes;

        /**
         * 每条路径归哪个 id，没归属的那些是 null
         */
        private final String[] owners;

        Plan(Map<String, String> prefixes, String[] owners) {
            this.prefixes = prefixes;
            this.owners = owners;
        }

        public Map<String, String> getPrefixes() {
            return prefixes;
        }

        public String ownerOf(int index) {
            return index >= 0 && index < owners.length ? owners[index] : null;
        }

        public String pathOf(String id) {
            return prefixes.get(id);
        }

        public boolean isEmpty() {
            return prefixes.isEmpty();
        }
    }
}
