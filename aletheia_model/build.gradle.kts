// Founder 17/11/2026: Aletheia 1.5B offline model, delivered by Google Play together with the app.
// The GGUF (986 MB) is not in git: put it at src/main/assets/aletheia/aletheia-1_5b-qwen32-factualrepair-v3-ggufdetail-q4_k_m.gguf
// (from the Aletheia repo, models/aletheia_1_5b_qwen32_factualrepair_v3_ggufdetail_q4km_20260609).
plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("aletheia_model")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
