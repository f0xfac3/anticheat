"""Retired command retained only to explain the migration to the audited registry."""
raise SystemExit(
    "This trainer has been replaced. From the repository root use:\n"
    "  python analytics/lab.py --store <analytics.sqlite> init\n"
    "  python analytics/lab.py --store <analytics.sqlite> admit <manifest.json> <metadata.json>\n"
    "  python analytics/lab.py --store <analytics.sqlite> train <detection-id> --scope independent\n"
    "See analytics/README.md. Existing raw recordings are unchanged."
)
