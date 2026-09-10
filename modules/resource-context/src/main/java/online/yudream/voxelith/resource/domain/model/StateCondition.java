package online.yudream.voxelith.resource.domain.model;

import java.util.Map;

/**
 * multipart when 条件树。对一个方块状态（属性名 → 属性值）求布尔值。
 */
public sealed interface StateCondition {

    boolean matches(Map<String, String> state);

    /** 恒真（multipart 省略 when 时）。 */
    StateCondition ALWAYS = new Always();

    /** 恒真条件单例实现。 */
    record Always() implements StateCondition {
        @Override
        public boolean matches(Map<String, String> state) {
            return true;
        }
    }

    /**
     * 属性合取：每个属性名对应一组可接受值（"a|b" 语义），全部命中才算匹配。
     */
    record PropertySet(Map<String, java.util.Set<String>> acceptedValues) implements StateCondition {
        @Override
        public boolean matches(Map<String, String> state) {
            for (Map.Entry<String, java.util.Set<String>> entry : acceptedValues.entrySet()) {
                String actual = state.get(entry.getKey());
                if (actual == null || !entry.getValue().contains(actual)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * OR 析取：任一子条件命中即匹配。
     */
    record AnyOf(java.util.List<StateCondition> alternatives) implements StateCondition {
        @Override
        public boolean matches(Map<String, String> state) {
            for (StateCondition alternative : alternatives) {
                if (alternative.matches(state)) {
                    return true;
                }
            }
            return false;
        }
    }
}
