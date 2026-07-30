# Memory Index

- [SPEC.md §5 drift check](feedback_spec_md_drift_check.md) — SPEC.md event catalog drifts independently of ADR 0024/fixture test; grep new topic name in SPEC.md every review
- [New vs retrofit contract reviews](project_review_new_vs_retrofit_contracts.md) — git status first to see if producer/consumer prod code actually changed, or if it's retrofitted test coverage on existing behavior
- [P2-7b secured-loan FINANCIAL_ASSET](project_p2_7b_secured_loan_financial_asset.md) — enum-only contract change reviewed clean; check whether consumer reads the widened enum field before worrying about drift
