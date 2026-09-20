package online.yudream.voxelith.marker.domain;

/**
 * 标注样式。所有字段可空 = 用前端默认值。
 *
 * <p>与 {@code @yudream/voxelith-core} 的 {@code markerStyleSchema} 一一对应；
 * 颜色是 CSS 颜色串（{@code #rrggbb} 或 {@code rgba(...)}），由渲染层解释。</p>
 *
 * @param fillColor 填充色（面/盒/图钉底色）
 * @param lineColor 描边色（线、多边形轮廓、盒子边）
 * @param lineWidth 线宽（像素；WebGL 的 LineBasicMaterial 忽略该值，保留给后续线带实现）
 * @param opacity   不透明度 0~1
 * @param icon      图钉内的图标字符（如 {@code "🏫"}）
 * @param depthTest false = 穿透地形显示（总在最前）
 */
public record MarkerStyle(String fillColor, String lineColor, Double lineWidth,
                          Double opacity, String icon, Boolean depthTest) {

    public static final MarkerStyle DEFAULT = new MarkerStyle(null, null, null, null, null, null);

    public MarkerStyle {
        if (opacity != null && (opacity < 0 || opacity > 1)) {
            throw new IllegalArgumentException("opacity 必须在 0~1 之间，收到: " + opacity);
        }
        if (lineWidth != null && lineWidth <= 0) {
            throw new IllegalArgumentException("lineWidth 必须为正，收到: " + lineWidth);
        }
        if (fillColor != null && fillColor.isBlank()) {
            throw new IllegalArgumentException("fillColor 不能是空白字符串");
        }
        if (lineColor != null && lineColor.isBlank()) {
            throw new IllegalArgumentException("lineColor 不能是空白字符串");
        }
    }
}
