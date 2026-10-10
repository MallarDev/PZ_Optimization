"""The PZ Workshop page dashboard's SQL, shared by dashboards.py and workshop_stats.py --report (no dependencies).

DAILY is a CTE prefix ending in `daily(day, metric, value)`: one value per whole UTC day and metric, from
- workshop_stats (Steam, every 30 min): each day's last snapshot minus the previous day's last one (days with a gap in
  between give nothing), and the ratios of those,
- workshop_releases: each release's GitHub downloads spread over the hours it was the latest release
  (/releases/latest/download/ serves the latest one), so the page's one-line installer gets a value per day,
- workshop_comments, workshop_github_views.
Today (UTC) is left out: it is partial.
"""

# (metric, unit, what it is); the order is the before / after table's
METRICS = [
    ("visitors", "none", "new unique visitors of the Workshop page (Steam counts each account once, ever)"),
    ("new subs", "none", "new subscriptions (lifetime count)"),
    ("unsubscribes", "none", "new subscriptions minus the change of current subscribers"),
    ("net subs", "none", "change of current subscribers"),
    ("subs per 100 visitors", "none", "new subscriptions per 100 new unique visitors (subscribes need no page visit, so it can pass 100)"),
    ("unsubs per 100 subs", "none", "unsubscribes per 100 new subscriptions"),
    ("favourites per 100 visitors", "none", "new favourites (lifetime count) per 100 new unique visitors"),
    ("new favourites", "none", "new favourites (lifetime count)"),
    ("ratings", "none", "new ratings"),
    ("comments", "none", "comments posted that day (timestamps of every comment)"),
    ("comments per 1k visitors", "none", "comments per 1,000 new unique visitors"),
    ("one-liner downloads", "none", "GitHub install.ps1 + install.sh downloads: the page's \"No window?\" one-liner only "
                                    "(the helper window and the by-hand copy install from the Workshop item's own files)"),
    ("zip downloads", "none", "GitHub release zip downloads: mostly the in-game updater and players without a Workshop copy"),
    ("releases", "none", "releases published that day (each one is a Workshop update too)"),
    ("github views", "none", "views of the GitHub repository pages (owner-only traffic data)"),
    ("github visitors", "none", "unique visitors of the GitHub repository pages"),
]

TODAY = "(now() AT TIME ZONE 'UTC')::date"

DAILY = f"""WITH s AS (
  SELECT (t AT TIME ZONE 'UTC')::date AS day,
    (array_agg(visitors ORDER BY t DESC))[1] AS visitors,
    (array_agg(lifetime_subscribers ORDER BY t DESC))[1] AS lsubs,
    (array_agg(subscribers ORDER BY t DESC))[1] AS subs,
    (array_agg(lifetime_favorites ORDER BY t DESC))[1] AS lfavs,
    (array_agg(ratings ORDER BY t DESC) FILTER (WHERE ratings IS NOT NULL))[1] AS ratings
  FROM workshop_stats GROUP BY 1),
sd AS (
  SELECT day, visitors - lag(visitors) OVER w AS visitors, lsubs - lag(lsubs) OVER w AS new_subs,
    subs - lag(subs) OVER w AS net_subs, lfavs - lag(lfavs) OVER w AS new_favs, ratings - lag(ratings) OVER w AS ratings,
    day - lag(day) OVER w AS gap
  FROM s WINDOW w AS (ORDER BY day)),
st AS (SELECT * FROM sd WHERE gap = 1),
cm AS (SELECT (t AT TIME ZONE 'UTC')::date AS day, sum(n)::float AS n FROM workshop_comments GROUP BY 1),
rl AS (SELECT t, coalesce(lead(t) OVER (ORDER BY t), now()) AS t2, installer, zip FROM workshop_releases WHERE t IS NOT NULL),
rd AS (
  SELECT (d AT TIME ZONE 'UTC')::date AS day,
    sum(rl.installer * extract(epoch FROM least(rl.t2, d + interval '1 day') - greatest(rl.t, d)) / extract(epoch FROM rl.t2 - rl.t)) AS installer,
    sum(rl.zip * extract(epoch FROM least(rl.t2, d + interval '1 day') - greatest(rl.t, d)) / extract(epoch FROM rl.t2 - rl.t)) AS zip
  FROM rl, generate_series(((rl.t AT TIME ZONE 'UTC')::date)::timestamp AT TIME ZONE 'UTC', rl.t2, interval '1 day') d
  WHERE rl.t2 > rl.t AND d < rl.t2 GROUP BY 1),
rc AS (SELECT (t AT TIME ZONE 'UTC')::date AS day, count(*)::float AS n FROM workshop_releases GROUP BY 1),
daily_all(day, metric, value) AS (
  SELECT day, 'visitors', visitors::float FROM st UNION ALL
  SELECT day, 'new subs', new_subs FROM st UNION ALL
  SELECT day, 'unsubscribes', new_subs - net_subs FROM st UNION ALL
  SELECT day, 'net subs', net_subs FROM st UNION ALL
  SELECT day, 'subs per 100 visitors', 100.0 * new_subs / nullif(visitors, 0) FROM st UNION ALL
  SELECT day, 'unsubs per 100 subs', 100.0 * (new_subs - net_subs) / nullif(new_subs, 0) FROM st UNION ALL
  SELECT day, 'favourites per 100 visitors', 100.0 * new_favs / nullif(visitors, 0) FROM st UNION ALL
  SELECT day, 'new favourites', new_favs FROM st UNION ALL
  SELECT day, 'ratings', ratings FROM st WHERE ratings IS NOT NULL UNION ALL
  SELECT day, 'comments', n FROM cm UNION ALL
  SELECT st.day, 'comments per 1k visitors', 1000.0 * cm.n / nullif(st.visitors, 0) FROM st JOIN cm USING (day) UNION ALL
  SELECT day, 'one-liner downloads', installer FROM rd UNION ALL
  SELECT day, 'zip downloads', zip FROM rd UNION ALL
  SELECT day, 'releases', n FROM rc UNION ALL
  SELECT day, 'github views', views FROM workshop_github_views UNION ALL
  SELECT day, 'github visitors', uniques FROM workshop_github_views),
daily AS (SELECT * FROM daily_all WHERE day < {TODAY} AND value IS NOT NULL)"""


def daily_series(metrics):
    """Time series (long format) of some daily metrics, one bar per day centred on noon UTC."""
    names = ", ".join("'" + m + "'" for m in metrics)
    return f"""{DAILY}
SELECT (day::timestamp AT TIME ZONE 'UTC') + interval '12 hours' AS time, metric, value FROM daily
WHERE metric IN ({names}) AND $__timeFilter(day::timestamp AT TIME ZONE 'UTC') ORDER BY 1"""


def before_after(change, days, trend):
    """Per metric: the mean of the `days` whole UTC days before the change day and after it (the change day itself left
    out), the range of the days before, and the after days against the log-linear trend of the `trend` days before
    (the counts were still falling after the 2026-09-20 launch, so a plain before / after mean reads every change as a
    loss). `change` is an SQL timestamptz expression, `days` / `trend` integers or Grafana variables."""
    order = " UNION ALL ".join(f"SELECT {i} AS o, '{m}' AS metric, '{d.replace(chr(39), chr(39) * 2)}' AS what"
                               for i, (m, _, d) in enumerate(METRICS))
    return f"""{DAILY},
cd AS (SELECT (({change}) AT TIME ZONE 'UTC')::date AS d),
x AS (SELECT daily.metric, daily.value, (daily.day - cd.d) AS x FROM daily, cd
      WHERE daily.day <> cd.d AND daily.day >= cd.d - {trend} AND daily.day <= cd.d + {days}),
f AS (
  SELECT metric,
    avg(value) FILTER (WHERE x < 0 AND x >= -{days}) AS before,
    min(value) FILTER (WHERE x < 0 AND x >= -{days}) AS lo,
    max(value) FILTER (WHERE x < 0 AND x >= -{days}) AS hi,
    avg(value) FILTER (WHERE x > 0) AS after,
    count(*) FILTER (WHERE x > 0) AS days_after,
    count(*) FILTER (WHERE x < 0 AND value > 0) AS n_fit,
    regr_slope(ln(value), x) FILTER (WHERE x < 0 AND value > 0) AS b,
    regr_intercept(ln(value), x) FILTER (WHERE x < 0 AND value > 0) AS a
  FROM x GROUP BY metric),
e AS (SELECT x.metric, avg(exp(f.a + f.b * x.x)) AS expected FROM x JOIN f USING (metric)
      WHERE x.x > 0 AND f.n_fit >= 3 GROUP BY 1),
o AS ({order})
SELECT o.metric, f.before AS "before", f.lo AS "before low", f.hi AS "before high", f.after AS "after",
  100.0 * (f.after / nullif(f.before, 0) - 1) AS "after vs before %",
  e.expected AS "trend expected", 100.0 * (f.after / nullif(e.expected, 0) - 1) AS "after vs trend %",
  100.0 * (exp(f.b) - 1) AS "trend before %/day", f.days_after AS "days after", o.what AS "what it counts"
FROM o JOIN f USING (metric) LEFT JOIN e USING (metric) ORDER BY o.o"""
