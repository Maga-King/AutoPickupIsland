package io.github.mio.autopickupfixture;

public final class CoffeePickupActivity extends NativePickupActivity {
    @Override String codeLabel() { return "取单口令"; }
    @Override Spec spec() {
        // Explicit test-app inputs only; useful for comparing local dictionary
        // names with opt-in cloud names without opening a real merchant page.
        String product = getIntent().getStringExtra("fixture_product");
        String code = getIntent().getStringExtra("fixture_code");
        if (product == null || product.isBlank() || product.length() > 160) product = "冰拿铁";
        if (code == null || !code.matches("[0-9]{1,6}\\.[\\p{IsHan}A-Za-z0-9]{1,16}")) code = "308.测试咖啡";
        return new Spec("星巴克咖啡", product, code, 0xFF00643C);
    }
}
