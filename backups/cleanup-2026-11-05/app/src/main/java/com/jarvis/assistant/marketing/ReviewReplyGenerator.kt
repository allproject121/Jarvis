package com.jarvis.assistant.marketing

/**
 * Customer Review Reply Drafter.
 * Automatically crafts authentic, brand-aligned, and conversion-focused responses
 * for customer reviews on Google Maps, Google Play Store, Zomato, and Swiggy.
 */
object ReviewReplyGenerator {

    enum class ReplyTone {
        PROFESSIONAL,
        WARM_AND_FRIENDLY,
        APOLOGETIC_AND_SOLUTION_ORIENTED,
        ENTHUSIASTIC
    }

    fun draftReply(
        reviewText: String,
        rating: Int,
        businessName: String = "Our Team",
        customerName: String? = null,
        language: String = "hinglish", // english, hindi, hinglish
        contactChannel: String? = "support team"
    ): String {
        val salutation = when {
            customerName.isNullOrBlank() && language == "hindi" -> "नमस्ते,"
            customerName.isNullOrBlank() && language == "hinglish" -> "Hi there,"
            customerName.isNullOrBlank() -> "Dear Customer,"
            language == "hindi" -> "नमस्ते ${customerName} जी,"
            else -> "Hi ${customerName},"
        }

        val cleanReview = reviewText.trim()

        return when {
            rating >= 5 -> generatePositiveReply(salutation, businessName, cleanReview, language)
            rating == 4 -> generateFourStarReply(salutation, businessName, cleanReview, language)
            rating == 3 -> generateNeutralReply(salutation, businessName, cleanReview, language, contactChannel)
            else -> generateNegativeReply(salutation, businessName, cleanReview, language, contactChannel)
        }
    }

    private fun generatePositiveReply(
        salutation: String,
        businessName: String,
        reviewText: String,
        language: String
    ): String {
        return when (language.lowercase()) {
            "hindi" -> """
                $salutation
                आपके शानदार 5-स्टार रिव्यू और प्रोत्साहन के लिए बहुत-बहुत धन्यवाद! यह जानकर हमें बेहद खुशी हुई कि आपको $businessName की सेवाएं पसंद आईं।
                हम आपके अगले अनुभव को और भी खास बनाने के लिए हमेशा तैयार हैं।
                
                सादर,
                $businessName टीम
            """.trimIndent()

            "hinglish" -> """
                $salutation
                Thank you so much for the 5-star review! Aapke positive feedback ne hamara din bana diya.
                $businessName par hamesha best service provide karna hamari priority hai. Agli baar bhi aapka experience itna hi shaandar rahega!
                
                Warm regards,
                Team $businessName
            """.trimIndent()

            else -> """
                $salutation
                Thank you so much for the glowing 5-star review! We are thrilled to hear that you had an exceptional experience with $businessName.
                Our team takes great pride in delivering top-notch service, and your kind words mean the world to us. We look forward to serving you again soon!
                
                Warm regards,
                The $businessName Team
            """.trimIndent()
        }
    }

    private fun generateFourStarReply(
        salutation: String,
        businessName: String,
        reviewText: String,
        language: String
    ): String {
        return when (language.lowercase()) {
            "hindi" -> """
                $salutation
                आपकी सकारात्मक समीक्षा के लिए धन्यवाद। हमें खुशी है कि आपको हमारी सेवा पसंद आई। हम इसे 5-स्टार अनुभव में बदलने के लिए लगातार सुधार कर रहे हैं।
                
                सादर,
                $businessName
            """.trimIndent()

            "hinglish" -> """
                $salutation
                Thanks a lot for the great 4-star rating! Khushi hui dekh kar ki aapka experience accha raha. Hamara goal isko pura 5-star banana hai, aur hum feedback par continuously improve kar rahe hain.
                
                Cheers,
                Team $businessName
            """.trimIndent()

            else -> """
                $salutation
                Thank you for taking the time to share your 4-star review! We are delighted that you enjoyed your experience with $businessName. We are always striving for 5-star perfection and would love to exceed your expectations on your next visit.
                
                Best regards,
                The $businessName Team
            """.trimIndent()
        }
    }

    private fun generateNeutralReply(
        salutation: String,
        businessName: String,
        reviewText: String,
        language: String,
        contactChannel: String?
    ): String {
        val contactInfo = contactChannel ?: "our helpdesk"
        return when (language.lowercase()) {
            "hindi" -> """
                $salutation
                आपके ईमानदार फीडबैक के लिए धन्यवाद। हमें खेद है कि आपका अनुभव हमारी सामान्य अपेक्षाओं के अनुरूप पूरी तरह नहीं रहा। हम अपनी सेवा को बेहतर बनाने के लिए आपके सुझावों का स्वागत करते हैं।
                कृपया $contactInfo पर संपर्क करें ताकि हम आपकी बेहतर सहायता कर सकें।
                
                सादर,
                $businessName
            """.trimIndent()

            "hinglish" -> """
                $salutation
                Thank you for your valuable feedback. Hamein dukh hai ki aapka experience 100% satisfactory nahi raha. Hum continuous improvement mein believe karte hain.
                Aap please $contactInfo par directly reach out karein taaki hum issue ko turant solve kar sakein.
                
                Sincerely,
                Team $businessName
            """.trimIndent()

            else -> """
                $salutation
                Thank you for sharing your feedback with us. While we are glad you reached out, we regret that your experience did not fully meet your expectations. We take all feedback seriously and are committed to doing better.
                Please reach out to $contactInfo so we can address your specific concerns and make things right.
                
                Sincerely,
                The $businessName Team
            """.trimIndent()
        }
    }

    private fun generateNegativeReply(
        salutation: String,
        businessName: String,
        reviewText: String,
        language: String,
        contactChannel: String?
    ): String {
        val contactInfo = contactChannel ?: "our dedicated support desk"
        return when (language.lowercase()) {
            "hindi" -> """
                $salutation
                हमें आपके असंतोषजनक अनुभव के लिए गहरा खेद है। $businessName में हमारे ग्राहकों की संतुष्टि सर्वोपरि है, और यह निराशाजनक है कि हम आपकी उम्मीदों पर खरे नहीं उतरे।
                हम इस समस्या को प्राथमिकता के आधार पर हल करना चाहते हैं। कृपया सीधे $contactInfo पर संपर्क करें ताकि हम तुरंत समाधान निकाल सकें।
                
                खेद सहित,
                $businessName प्रबंधन
            """.trimIndent()

            "hinglish" -> """
                $salutation
                We sincerely apologize for the inconvenience and poor experience you faced. $businessName par client satisfaction hamari top priority hai, aur yeh sunkar bohot afsos hua ki aapko pareshani hui.
                Hum is matter ko directly investigate karke fix karna chahte hain. Please $contactInfo par contact karein taaki hum isko personally resolve karein.
                
                Sincerely,
                Customer Care, $businessName
            """.trimIndent()

            else -> """
                $salutation
                We sincerely apologize for your disappointing experience. At $businessName, we hold ourselves to the highest standards, and it is clear we fell short in this instance.
                We want to investigate what happened and make things right immediately. Please reach out to us directly at $contactInfo with your details so our management can assist you personally.
                
                With sincere apologies,
                The $businessName Management Team
            """.trimIndent()
        }
    }
}
