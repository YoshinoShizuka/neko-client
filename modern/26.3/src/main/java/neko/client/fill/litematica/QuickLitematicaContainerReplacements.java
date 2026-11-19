// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill.litematica;

import neko.client.fill.QuickContainerCopy;

/** 投影容器替换规则在批次 A 不启用：模板原样返回。 */
public final class QuickLitematicaContainerReplacements {
    private QuickLitematicaContainerReplacements() { }
    public static QuickContainerCopy.TemplateSnapshot applyToSnapshot(QuickContainerCopy.TemplateSnapshot snapshot) {
        return snapshot;
    }
}
