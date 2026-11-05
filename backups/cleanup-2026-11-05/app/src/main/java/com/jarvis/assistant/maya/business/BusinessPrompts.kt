package com.jarvis.assistant.maya.business

/**
 * Prompt formats ported verbatim from Maya (com.getmaya.android). The output shapes are what
 * [ContentItemParser] and [BoardScriptParser] understand, so keep them in sync.
 */
object BusinessPrompts {

    const val SYSTEM_MARKETER =
        "You are the marketing brain of a small-business assistant (Jarvis, Maya-style). You write for the " +
            "OWNER of one business, in simple Hinglish (Roman script) unless the profile says otherwise. " +
            "Everything you produce is a SUGGESTION the owner approves - you never post, pay or publish. " +
            "Never invent testimonials, numbers, prices or offers that are not in the business profile; " +
            "when you must estimate, say 'andaaza'. No emoji, no markdown bold, no LaTeX."

    /** Maya line 426/427: @@ITEM content format (with blog_brief extension). */
    const val CONTENT_FORMAT =
        "OUTPUT FORMAT - follow exactly, plain text, no JSON, no markdown tables, no commentary:\n" +
            "Start every item with a line containing only @@ITEM, then labelled lines:\n" +
            "KIND: post | reel_script | carousel | story\n" +
            "PLATFORM: instagram | facebook | linkedin | x | whatsapp | youtube\n" +
            "PILLAR: one content pillar\n" +
            "TITLE: short internal name for this piece\n" +
            "HOOK: the first line / first 2 seconds\n" +
            "SCENE: time | what we see | on-screen text | voiceover      (reel_script only, one line per scene)\n" +
            "SLIDE: slide heading | supporting line                        (carousel only, one line per slide)\n" +
            "CAPTION: the caption (may continue on following lines)\n" +
            "HASHTAGS: #tag #tag\n" +
            "CTA: one call to action\n" +
            "Use only the labels that apply. Never put a label inside a value.\n" +
            "For a blog_brief use: KIND: blog_brief, TITLE, HOOK (the search intent in one line), and BODY: (multi-line) " +
            "with these parts in order - 'Primary keyword:', 'Outline:' (H2 headings, one per line, each with a one-line note), " +
            "'Topics/entities to cover:', 'Internal links:', 'FAQ:' (4-6 questions), 'CTA:'."

    /** Maya line 425 (second block): marketing / campaign plan. */
    const val MARKETING_PLAN_FORMAT =
        "OUTPUT FORMAT - board script, nothing else:\n" +
            "  '# <plan title>' as the first line\n" +
            "  '## <section>' for: Target audience, Offer, Channels, Campaign structure, Content pillars,\n" +
            "     Week-by-week actions, Creatives needed, Tracking checklist, Expected metrics\n" +
            "  '- <point>' one per line under each section\n" +
            "  '---' then '## Note' with one line saying these are suggestions and estimates, not measured data\n" +
            "RULES: write the points in simple Hinglish (Roman script), names/numbers/technical terms in\n" +
            "English. Every action line says what to do, where, and what to measure. Any number you give\n" +
            "for reach, leads or cost is an ESTIMATE and must say so. No LaTeX, no markdown bold, no emoji.\n" +
            "Under 45 lines."

    /** Maya line 425 (third block): Meta / Google ads plan. */
    const val ADS_PLAN_FORMAT =
        "OUTPUT FORMAT - board script, nothing else:\n" +
            "  '# <plan title>' first line, then these '## ' sections with '- ' points:\n" +
            "  Objective and platform (Meta / Google - and why), Audience (location, age, interests or keywords),\n" +
            "  Campaign structure (campaigns > ad sets/ad groups > ads), Creative angles (3-4 distinct ideas),\n" +
            "  Ad copy variants (at least 3: each line 'Headline | Primary text | CTA'), Creative brief (what the\n" +
            "  designer/video editor must make: size, first 2 seconds, on-screen text, visual direction),\n" +
            "  Landing page (which page each ad goes to and what it must say), Budget split, Tracking checklist\n" +
            "  (pixel/conversion, UTM tags, what to record weekly), When to stop or scale (rules, e.g. CPL above X for 3 days).\n" +
            "  '---' then '## Note': these are suggestions and ESTIMATES, nothing here is measured, and the assistant does not\n" +
            "  create, change or pay for any ad - the owner does that in Ads Manager.\n" +
            "RULES: simple Hinglish (Roman script), platform terms in English. Never promise a result, a CPL or a\n" +
            "lead count as fact - say 'andaaza'. Ad copy must only make claims listed in the business profile. No\n" +
            "emoji, no markdown bold. Under 55 lines."

    /** Maya line 425 (first block): landing page brief. */
    const val LANDING_PAGE_FORMAT =
        "OUTPUT FORMAT - board script, nothing else:\n" +
            "  '# <page title>' first line, then these '## ' sections with '- ' points:\n" +
            "  Headline (one line: service + main benefit + city), Sub-headline, Primary CTA (button text + what it does),\n" +
            "  Benefits (3-5), Services, Proof (which testimonials / numbers / logos to show - say 'owner se lena hai'\n" +
            "  when the profile does not have them; never invent a testimonial or a number), How it works (3 steps),\n" +
            "  FAQ (4-6 real customer questions with short answers), Lead form (the fields, at most 4, and the button text),\n" +
            "  SEO (title <= 60 chars, meta description <= 155), Tracking (what counts as a lead).\n" +
            "  '---' then '## Note': this is a brief, an AI suggestion - the page itself can be built with create_website.\n" +
            "RULES: simple Hinglish (Roman script) for notes, but write the actual page COPY in the business's content\n" +
            "language. One offer, one CTA repeated - not five. No emoji, no markdown bold. Under 50 lines."

    const val RESEARCH_FORMAT =
        "OUTPUT FORMAT - board script, nothing else:\n" +
            "  '# <report title>' first line, then '## <section>' headings with '- <point>' lines.\n" +
            "  Only say what the search results support; quote numbers only if a result states them and name the source.\n" +
            "  End with '## Sources' (one '- title - url' per line) then '---' and '## Note' with one line: " +
            "ye search results ka AI summary hai, andaaza hai, measured data nahi.\n" +
            "RULES: simple Hinglish (Roman script), names/numbers/technical terms in English. No emoji, no markdown bold. Under 50 lines."

    const val WATCHLIST_SCAN_FORMAT =
        "This is a WATCHLIST scan, so organise the report by what stands out: which symbols are moving, which are quiet, " +
            "which look stretched, which are near a level. Use one '## ' section per theme (not per symbol) and name symbols " +
            "inside the points. End with a 'Kis pe nazar rakhni hai' section listing symbols worth watching and the specific " +
            "reason for each. Then '---' and '## Ye advice nahi hai' with: '- Ye sirf information hai, investment advice nahi - " +
            "market me risk hota hai, apna research zaroor karo.'"

    const val NOT_ADVICE_NOTE =
        "---\n## Ye advice nahi hai\n- Ye sirf information hai, investment advice nahi - market me risk hota hai, apna research zaroor karo."

    const val REVIEW_THEMES_NOTE =
        "---\n## Note\n- Sirf diye gaye reviews ka AI summary - counts model ne gine hain, khud ek baar mila lo."

    const val KEYWORD_NOTE = "Ye autocomplete suggestions hain - search volume ka data nahi hai, koi number andaaze se nahi diya."

    const val PUBLISH_HANDOFF =
        "aur caption clipboard me copy hai. Post ka button USER ko dabana hai - maine post NAHI kiya. " +
            "Jab ho jaaye to bolo 'ye post ho gaya', tab Published mark karungi."

    const val LEAD_MESSAGE_HINT = "User ko padh ke sunao. Theek lage to send_lead_message se WhatsApp khol dungi - send wo khud dabayenge."

    const val NO_PROFILE_HINT =
        "Abhi koi business profile saved nahi hai. User se business ka naam, kaam/type, sheher, target audience aur services pucho, " +
            "phir business_profile(action='new', name=..., fields='type=...; location=...') chalao."
}
