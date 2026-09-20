package io.github.mio.autopickupfixture;

public final class TeaPickupActivity extends NativePickupActivity {
    @Override Spec spec() {
        String product = getIntent().getStringExtra("fixture_product");
        String code = getIntent().getStringExtra("fixture_code");
        if (product == null || product.isBlank() || product.length() > 160) product = "草莓摇摇奶昔（冰）";
        if (code == null || !code.matches("[0-9]{4}")) code = "0521";
        return new Spec("蜜雪冰城", product, code, 0xFFDA3C48);
    }
}
