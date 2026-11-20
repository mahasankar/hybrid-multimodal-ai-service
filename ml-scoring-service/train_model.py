import os
import json
import numpy as np
import pandas as pd
import xgboost as xgb
from sklearn.model_selection import train_test_split
from sklearn.metrics import roc_auc_score, classification_report


FEATURE_COLUMNS = [
    "claim_amount",
    "amount_to_role_avg_ratio",
    "employee_tenure_months",
    "claims_last_30_days",
    "is_weekend_submission",
    "is_high_risk_category",  # 1 = Gift Cards / Luxury / Unverified Vendor, 0 = Travel / Meals / Software
    "receipt_text_confidence",  # 0.0 to 1.0 (from Vision/OCR extraction)
]


def generate_synthetic_expense_data(n_samples: int = 5000, seed: int = 42) -> pd.DataFrame:
    """Generates realistic historical corporate expense claims with latent fraud patterns."""
    rng = np.random.default_rng(seed)

    claim_amount = np.round(rng.lognormal(mean=4.8, sigma=0.9, size=n_samples), 2)
    claim_amount = np.clip(claim_amount, 10.0, 5000.0)

    employee_tenure_months = rng.integers(1, 96, size=n_samples)
    claims_last_30_days = rng.poisson(lam=2.2, size=n_samples)
    is_weekend_submission = rng.binomial(n=1, p=0.18, size=n_samples)
    is_high_risk_category = rng.binomial(n=1, p=0.12, size=n_samples)
    receipt_text_confidence = np.round(rng.uniform(0.75, 1.0, size=n_samples), 3)

    # Role baseline average around $150; ratio measures how abnormal the claim is for that user
    role_baseline = rng.uniform(100.0, 350.0, size=n_samples)
    amount_to_role_avg_ratio = np.round(claim_amount / role_baseline, 2)

    # Realistic non-linear fraud signal (log-odds)
    fraud_logit = (
        -3.6
        + 0.45 * np.maximum(0, amount_to_role_avg_ratio - 2.0)
        + 1.65 * is_high_risk_category
        + 0.85 * is_weekend_submission
        + 0.35 * np.maximum(0, claims_last_30_days - 4)
        + np.where(employee_tenure_months < 4, 0.90, 0.0)
        + np.where(receipt_text_confidence < 0.82, 1.10, 0.0)
        + rng.normal(0, 0.45, size=n_samples)
    )

    fraud_prob = 1 / (1 + np.exp(-fraud_logit))
    is_fraud = (rng.uniform(0, 1, size=n_samples) < fraud_prob).astype(int)

    return pd.DataFrame({
        "claim_amount": claim_amount,
        "amount_to_role_avg_ratio": amount_to_role_avg_ratio,
        "employee_tenure_months": employee_tenure_months,
        "claims_last_30_days": claims_last_30_days,
        "is_weekend_submission": is_weekend_submission,
        "is_high_risk_category": is_high_risk_category,
        "receipt_text_confidence": receipt_text_confidence,
        "is_fraud": is_fraud,
    })


def train_and_save_model():
    print("=== 1. Generating 5,000 Historical Corporate Expense Claims ===")
    df = generate_synthetic_expense_data(n_samples=5000)
    fraud_rate = df["is_fraud"].mean() * 100
    print(f"Dataset shape: {df.shape} | Historical Fraud Rate: {fraud_rate:.2f}%\n")

    X = df[FEATURE_COLUMNS]
    y = df["is_fraud"]

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    print("=== 2. Training XGBoost Classifier ===")
    model = xgb.XGBClassifier(
        n_estimators=150,
        max_depth=4,
        learning_rate=0.08,
        subsample=0.85,
        colsample_bytree=0.85,
        eval_metric="logloss",
        random_state=42,
    )
    model.fit(X_train, y_train)

    print("=== 3. Evaluating Out-of-Sample Performance ===")
    y_pred_proba = model.predict_proba(X_test)[:, 1]
    y_pred = (y_pred_proba >= 0.50).astype(int)

    auc = roc_auc_score(y_test, y_pred_proba)
    print(f"Test ROC-AUC Score: {auc:.4f}\n")
    print("Classification Report (Threshold = 0.50):")
    print(classification_report(y_test, y_pred, target_names=["Legitimate", "Fraud"]))

    print("=== 4. Learned Feature Importances ===")
    importances = sorted(
        zip(FEATURE_COLUMNS, model.feature_importances_),
        key=lambda x: x[1],
        reverse=True,
    )
    for feat, score in importances:
        print(f"  * {feat:<28}: {score:.4f}")

    os.makedirs("models", exist_ok=True)
    model_path = os.path.join("models", "expense_fraud_xgb.json")
    metadata_path = os.path.join("models", "model_metadata.json")

    model.save_model(model_path)
    with open(metadata_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "model_version": "1.0.0",
                "features": FEATURE_COLUMNS,
                "roc_auc": round(float(auc), 4),
                "risk_thresholds": {"low": 0.30, "high": 0.70},
            },
            f,
            indent=2,
        )

    print(f"\nModel saved to {model_path}")
    print(f"Metadata saved to {metadata_path}")


if __name__ == "__main__":
    train_and_save_model()