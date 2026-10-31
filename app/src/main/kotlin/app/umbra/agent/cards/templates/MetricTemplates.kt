package app.umbra.agent.cards.templates

import app.umbra.agent.cards.*

/** These layouts arrange supplied facts; examples document the input contract for the agent. */
internal fun metricTemplates(): List<CardTemplate> = listOf(
    template("Metrics", "kpi", "KPI", "activity",
        "one metric with a sparkline and delta", "figure* unit* points* delta*",
        """{"title":"Weekly active users","figure":"2,480","unit":"users","points":[{"label":"Mon","value":2110},{"label":"Tue","value":2180},{"label":"Wed","value":2260},{"label":"Thu","value":2310},{"label":"Fri","value":2400},{"label":"Sat","value":2450},{"label":"Sun","value":2480}],"delta":"+8% from last week"}""",
    ) { figure(caption = required("delta")); line(spark = true) },
    template("Metrics", "kpi_grid", "Metrics", "grid",
        "several headline metrics", "stats*",
        """{"title":"Product metrics","stats":[{"label":"Users","value":"2,480"},{"label":"Activation","value":"72","unit":"%"}]}""",
    ) { stats() },
    template("Metrics", "trend", "Trend", "activity",
        "a labelled time series and context numbers", "points* unit* stats*",
        """{"title":"Weekly activity","points":[{"label":"Mon","value":4},{"label":"Tue","value":6},{"label":"Wed","value":5},{"label":"Thu","value":7},{"label":"Fri","value":6},{"label":"Sat","value":3},{"label":"Sun","value":4}],"unit":"h","stats":[{"label":"Meetings","value":"3"},{"label":"Focus time","value":"4","unit":"h"}]}""",
    ) { line(); stats() },
    template("Metrics", "comparison_bars", "Comparison", "layers",
        "comparing numeric magnitudes", "bars* unit*",
        """{"title":"Time by project","bars":[{"label":"Research","value":12},{"label":"Design","value":8}],"unit":"h"}""",
    ) { bars() },
    template("Metrics", "breakdown", "Breakdown", "grid",
        "parts of a whole", "slices* unit* details",
        """{"title":"October spending","slices":[{"label":"Food","value":240},{"label":"Transport","value":80},{"label":"Books","value":60}],"unit":"USD","details":{"Period":"Oct 1–28"}}""",
    ) { donut(); kv() },
    template("Metrics", "leaderboard", "Leaderboard", "users",
        "a ranked list with values", "items*",
        """{"title":"Reading challenge","items":[{"title":"Maya","trailing":"8 books"},{"title":"Alex","trailing":"6 books"}]}""",
    ) { items(numbered = true) },
    template("Metrics", "data_table", "Table", "grid",
        "a compact dataset", "columns* rows*",
        """{"title":"Study sessions","columns":["Day","Topic","Minutes"],"rows":[["Monday","Kotlin","45"],["Tuesday","Testing","30"]]}""",
    ) { table() },
    template("Metrics", "funnel", "Funnel", "filter",
        "counts and conversion percentages by stage", "stages* unit*",
        """{"title":"Signup funnel","stages":[{"label":"Visits","count":1000,"percentage":100},{"label":"Signups","count":240,"percentage":24}],"unit":"people"}""",
    ) { val stages = objects("stages").map {
            val count = it.number("count"); val percent = it.number("percentage")
            if (count < 0) it.error("count", "must be nonnegative")
            if (percent !in 0.0..100.0) it.error("percentage", "must be between 0 and 100")
            CardBlock.Bars.Bar(it.required("label", 80), count, "${count.toString().removeSuffix(".0")} (${percent.toString().removeSuffix(".0")}%)")
        }
        add(CardBlock.Bars(stages, unit = required("unit", 80))) },
    template("Metrics", "before_after", "Before / after", "swap",
        "two versions compared on matching aspects", "aspects* variants*",
        """{"title":"Onboarding update","aspects":["Time","Steps"],"variants":[{"title":"Before","values":["40 min","3 steps"]},{"title":"After","values":["25 min","2 steps"]}]}""",
    ) { compare(); if (objects("variants", 5).size != 2) error("variants", "requires exactly two columns") },
    template("Metrics", "ab_test", "A/B test", "fork",
        "two test variants and a supplied result", "aspects* variants* result*",
        """{"title":"Signup experiment","aspects":["Conversion","Sample"],"variants":[{"title":"A","values":["12%","1,000"]},{"title":"B","values":["14%","1,020"]}],"result":"B converted more visitors in this sample; significance has not been established."}""",
    ) { compare(); callout("result") },
    template("Metrics", "distribution", "Distribution", "layers",
        "values across bins", "points* unit*",
        """{"title":"Response times","points":[{"label":"0–1","value":4},{"label":"1–2","value":8},{"label":"2–3","value":12},{"label":"3–4","value":6},{"label":"4–5","value":2}],"unit":"requests"}""",
    ) { columns() },
    template("Metrics", "survey_results", "Survey", "users",
        "survey percentages by answer", "bars*",
        """{"title":"Preferred work location","bars":[{"label":"Home","value":60},{"label":"Office","value":40}]}""",
    ) { objects("bars").forEach { if (it.number("value") !in 0.0..100.0) it.error("value", "must be between 0 and 100") }; bars(unit = "%", max = 100.0) },
    template("Metrics", "scorecard", "Score", "gauge",
        "a score on a known scale", "value* min* max* details*",
        """{"title":"Accessibility audit","value":86,"min":0,"max":100,"details":{"Checks passed":"43 of 50","Scope":"Landing page"}}""",
    ) { val low = number("min"); val high = number("max"); val score = number("value")
        if (high <= low || !(high - low).isFinite()) error("max", "must be greater than min with a finite range")
        if (score !in low..high) error("value", "must be within min and max")
        add(CardBlock.Gauge(score, low, high, text("label", 80), text("display", 80), tone())); kv() },
    template("Metrics", "anomaly", "Anomaly", "alert",
        "an unusual measurement needing attention", "text* points* unit*",
        """{"title":"Latency spike","text":"Latency increased during the 14:00 window.","points":[{"label":"Mon","value":4},{"label":"Tue","value":6},{"label":"Wed","value":5},{"label":"Thu","value":7},{"label":"Fri","value":6},{"label":"Sat","value":3},{"label":"Sun","value":4}],"unit":"ms"}""",
    ) { callout(tone = CardTone.WARN); line(); cardTone = CardTone.WARN },
)
