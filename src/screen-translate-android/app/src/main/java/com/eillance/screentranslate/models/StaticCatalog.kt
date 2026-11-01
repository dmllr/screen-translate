package com.eillance.screentranslate.models

import com.eillance.screentranslate.lang.Script

/**
 * OCR and language-ID models. All are pinned to an exact upstream revision and SHA-256.
 *
 * OCR: official PaddlePaddle ONNX exports on Hugging Face (Apache-2.0).
 *  - PP-OCRv6 small recognizer reads CJK (Chinese, Japanese kana + kanji), Latin-script
 *    languages and Greek with a single model, so most screens need nothing else.
 *  - PP-OCRv5 mobile recognizers cover the remaining scripts as optional add-ons.
 * Language ID: fastText lid.176.ftz from Meta (CC BY-SA 3.0).
 */
object StaticCatalog {

  const val DETECTOR_DEFAULT = "ocr-det-v6-small"
  const val DETECTOR_TINY = "ocr-det-v6-tiny"
  const val RECOGNIZER_CORE = "ocr-rec-v6-small"
  const val LANGUAGE_ID = "langid-fasttext-176"

  private fun hf(repo: String, revision: String, file: String) =
    "https://huggingface.co/PaddlePaddle/$repo/resolve/$revision/$file"

  private fun paddle(
    id: String,
    kind: PackKind,
    title: String,
    description: String,
    repo: String,
    revision: String,
    onnxSha: String,
    onnxSize: Long,
    ymlSha: String,
    ymlSize: Long,
    scripts: Set<Script> = emptySet(),
    rtl: Boolean = false,
  ) = ModelPack(
    id = id,
    kind = kind,
    title = title,
    description = description,
    version = revision.take(8),
    files = listOf(
      RemoteFile(hf(repo, revision, "inference.onnx"), "inference.onnx", onnxSha, onnxSize),
      RemoteFile(hf(repo, revision, "inference.yml"), "inference.yml", ymlSha, ymlSize),
    ),
    scripts = scripts,
    rightToLeft = rtl,
  )

  val detectors: List<ModelPack> = listOf(
    paddle(
      DETECTOR_DEFAULT, PackKind.OCR_DETECTOR, "Text detector (PP-OCRv6 small)",
      "Finds text on screen. Recommended.",
      "PP-OCRv6_small_det_onnx", "28fe5895c24fd108c19eb3e8479f4ab385fbfc62",
      "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e", 9_880_512,
      "193f435274bf9f0b5f71a929bbfbcf148282df7e633b34e7c373e8f44741b516", 885,
    ),
    paddle(
      DETECTOR_TINY, PackKind.OCR_DETECTOR, "Text detector (PP-OCRv6 tiny)",
      "Faster, less accurate. For low-end devices or continuous mode.",
      "PP-OCRv6_tiny_det_onnx", "2ba1506c0380b8f0b03dd142459aac66d4421f6c",
      "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8", 1_780_590,
      "3ac018be6f97499a08faa3bbdeb33640968d9307f6736d152902747a9f259593", 883,
    ),
  )

  val recognizers: List<ModelPack> = listOf(
    paddle(
      RECOGNIZER_CORE, PackKind.OCR_RECOGNIZER, "Text reader: CJK + Latin + Greek (PP-OCRv6)",
      "Japanese, Chinese, English and other Latin-script languages. Required.",
      "PP-OCRv6_small_rec_onnx", "b8f84f0b80c529de40b4fbb3544b84fa7233a513",
      "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634", 21_159_378,
      "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1", 150_579,
      setOf(Script.HAN, Script.KANA, Script.LATIN, Script.GREEK),
    ),
    paddle(
      "ocr-rec-korean", PackKind.OCR_RECOGNIZER, "Text reader: Korean", "Hangul (PP-OCRv5).",
      "korean_PP-OCRv5_mobile_rec_onnx", "5c6f574b8e2230adf4287b33e736d71b9fabd28e",
      "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08", 13_418_787,
      "f757fa1c40e99edcf27e9cce879b93eb2a51fa46f5ef39095689b8c37dd75998", 96_039,
      setOf(Script.HANGUL, Script.LATIN),
    ),
    paddle(
      "ocr-rec-cyrillic", PackKind.OCR_RECOGNIZER, "Text reader: Cyrillic",
      "Russian, Ukrainian, Bulgarian, Serbian, Belarusian… (PP-OCRv5).",
      "cyrillic_PP-OCRv5_mobile_rec_onnx", "2cef88145434beb8afa9dd82d77d799eb1ad7b29",
      "5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5", 8_048_799,
      "5c76cc91fa98410178a09f498db10050d0ec1634a660053d3005ab7be581f501", 6_991,
      setOf(Script.CYRILLIC, Script.LATIN),
    ),
    paddle(
      "ocr-rec-arabic", PackKind.OCR_RECOGNIZER, "Text reader: Arabic script",
      "Arabic, Persian, Urdu, Uyghur (PP-OCRv5).",
      "arabic_PP-OCRv5_mobile_rec_onnx", "14aaedcd75825982689ecf5cd64ab33ee083215a",
      "799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110", 7_998_947,
      "21368419e6c016c31db55d316d59e11c128e1913e6e6fe10287084710043d3a6", 6_165,
      setOf(Script.ARABIC), rtl = true,
    ),
    paddle(
      "ocr-rec-devanagari", PackKind.OCR_RECOGNIZER, "Text reader: Devanagari", "Hindi, Marathi (PP-OCRv5).",
      "devanagari_PP-OCRv5_mobile_rec_onnx", "251aec19e36739540d35e2cc943f6aa7503b98e5",
      "cb789212ce96c69d3e74728ae4309d179281d68cb3945d0616b67cafab41c986", 7_912_311,
      "9bd172dd26440c8ce94d1cde5d5baea6aefdc7cf3c5c8492e0beedef656d4e54", 5_027,
      setOf(Script.DEVANAGARI),
    ),
    paddle(
      "ocr-rec-thai", PackKind.OCR_RECOGNIZER, "Text reader: Thai", "Thai (PP-OCRv5).",
      "th_PP-OCRv5_mobile_rec_onnx", "1d4adbbafb1034a2fd6618498575b81ea7b69f69",
      "27618be66018f8598ac0a526a593f9f1cebf794e7eded93428e8fb016e537f5f", 7_891_015,
      "f6ba7fefc38ca1ff398ddafa75d67d16e0b3757c4e6c833adffee98a981766c9", 4_667,
      setOf(Script.THAI),
    ),
    paddle(
      "ocr-rec-greek", PackKind.OCR_RECOGNIZER, "Text reader: Greek (dedicated)",
      "Better Greek accuracy than the core reader (PP-OCRv5).",
      "el_PP-OCRv5_mobile_rec_onnx", "8152b89d2ee0e1d4c92decab75fe75e5e3d836b6",
      "2acf17fcaea2bc81b878e311e6263b8885f48bb03796f75f9f30ed3242bbaa6d", 7_808_735,
      "17d85b2fe2d2f24cd4ab07bcbc33e0c126859b956ced36e281dc65e2d0c1f0bf", 3_317,
      setOf(Script.GREEK),
    ),
    paddle(
      "ocr-rec-tamil", PackKind.OCR_RECOGNIZER, "Text reader: Tamil", "Tamil (PP-OCRv5).",
      "ta_PP-OCRv5_mobile_rec_onnx", "0db89798af0d5218fad9ea7ace0557526e9077f1",
      "c6d2b682d2a0ea4cb1fccdba295976f93fd439964d16cdc666cadef531accbee", 7_885_691,
      "88a28f5a1bb30cabe38a0985cb5e6619fa4f0c7c78e57a08274674228c5219a6", 4_579,
      setOf(Script.TAMIL),
    ),
    paddle(
      "ocr-rec-telugu", PackKind.OCR_RECOGNIZER, "Text reader: Telugu", "Telugu (PP-OCRv5).",
      "te_PP-OCRv5_mobile_rec_onnx", "f1197c83430241c58c64b0952b3a80366d04eeba",
      "8238bfc46d4cffe720ed6706e3842802467343497428693ff2bfb4e6b3caa36b", 7_898_759,
      "acebbe53f1831bf28ddfed75aedf58225d7aa5d09100c1d5a9140a2a53b137ce", 4_795,
      setOf(Script.TELUGU),
    ),
  )

  val languageId = ModelPack(
    id = LANGUAGE_ID,
    kind = PackKind.LANGUAGE_ID,
    title = "Language identification (fastText lid.176)",
    description = "Detects which of 176 languages a text is in. Required for auto-detection.",
    version = "176",
    files = listOf(
      RemoteFile(
        "https://dl.fbaipublicfiles.com/fasttext/supervised-models/lid.176.ftz",
        "lid.176.ftz",
        "8f3472cfe8738a7b6099e8e999c3cbfae0dcd15696aac7d7738a8039db603e83",
        938_013,
      ),
    ),
  )

  /** Packs that must be installed before the app can do anything. */
  val required: List<String> = listOf(DETECTOR_DEFAULT, RECOGNIZER_CORE, LANGUAGE_ID)

  val all: List<ModelPack> = detectors + recognizers + languageId
}
