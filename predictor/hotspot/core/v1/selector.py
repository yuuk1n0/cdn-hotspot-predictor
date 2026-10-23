"""Select a simple candidate from past-only metrics."""

from typing import Mapping

from .candidates import COMPARISONS

NONINFERIORITY_MARGIN = 0.01  # one absolute percentage point
SELECTION_RULE = (
    "simplest method within 1 percentage point of best selection Recall@50 and NDCG@50"
)


def select_method(selection_results: Mapping[str, dict]) -> str:
    """Prefer the simplest method within 1 pp of best Recall and NDCG."""
    best_recall = max(result["mean"]["recallAt50"] for result in selection_results.values())
    best_ndcg = max(result["mean"]["ndcgAt50"] for result in selection_results.values())
    for name in COMPARISONS:  # declared from simplest to more involved
        means = selection_results[name]["mean"]
        if (means["recallAt50"] >= best_recall - NONINFERIORITY_MARGIN
                and means["ndcgAt50"] >= best_ndcg - NONINFERIORITY_MARGIN):
            return name
    # If the best Recall method is materially worse on NDCG, use Recall first.
    return max(COMPARISONS, key=lambda name: (
        selection_results[name]["mean"]["recallAt50"],
        selection_results[name]["mean"]["ndcgAt50"],
    ))
