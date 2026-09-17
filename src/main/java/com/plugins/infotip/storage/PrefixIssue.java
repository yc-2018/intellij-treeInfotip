package com.plugins.infotip.storage;

/**
 * 一条 {@code <prefix>} 声明的体检结果
 *
 * <p>
 * 给 {@code XmlPrefixDialog} 用：文本框里给失效的标红、没人引用的标黄，
 * 再按这个结果一键清理。文本区间是**声明标签在文本里的偏移量**，
 * 拿去 {@code Highlighter.addHighlight} 直接能用。
 * </p>
 *
 * @author yc556&claude-fable-5
 * @version 1.0
 */
public final class PrefixIssue {

    /**
     * 前缀 id
     */
    private final String id;

    /**
     * 前缀指向的完整路径
     */
    private final String path;

    /**
     * 声明标签在文本里的起止偏移量
     */
    private final int startOffset;

    private final int endOffset;

    /**
     * 有多少条规则引用了它
     */
    private final int referenceCount;

    /**
     * 路径在磁盘上已经不存在
     */
    private final boolean dead;

    PrefixIssue(String id, String path, int startOffset, int endOffset, int referenceCount, boolean dead) {
        this.id = id;
        this.path = path;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.referenceCount = referenceCount;
        this.dead = dead;
    }

    public String getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public int getReferenceCount() {
        return referenceCount;
    }

    public boolean isDead() {
        return dead;
    }

    /**
     * 没有任何规则引用它，纯粹是留在文件里的垃圾
     */
    public boolean isUnused() {
        return 0 == referenceCount;
    }

    /**
     * 有问题就算（失效或没人引用），只有这种才需要在界面上标出来
     */
    public boolean isProblem() {
        return dead || isUnused();
    }

    /**
     * 界面上显示的一行说明
     */
    public String describe() {
        if (dead && isUnused()) {
            return id + " → " + path + "（路径已失效，且没有规则引用）";
        }
        if (dead) {
            return id + " → " + path + "（路径已失效，还有 " + referenceCount + " 条规则引用它）";
        }
        return id + " → " + path + "（没有规则引用）";
    }
}
