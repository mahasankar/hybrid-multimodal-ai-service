import os
from pathlib import Path
import pandas as pd
import xgboost as xgb

class FraudModelService:
    def __init__(self, model_path: str = None):
        if model_path is None:
            # Dynamically find the project root regardless of execution directory
            BASE_DIR = Path(__file__).resolve().parent.parent
            model_path = BASE_DIR / "models" / "expense_fraud_xgb.json"
            
        self.model_path = str(model_path)
        self.model = None
        self.feature_columns = [
            "claim_amount",
            "amount_to_role_avg_ratio",
            "employee_tenure_months",
            "claims_last_30_days",
            "is_weekend_submission",
            "is_high_risk_category",
            "receipt_text_confidence",
        ]
        self.load_model()

    def load_model(self):
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(f"Trained model not found at {self.model_path}. Run train_model.py first.")
        
        self.model = xgb.XGBClassifier()
        self.model.load_model(self.model_path)
        print(f"[ML Service] Successfully loaded XGBoost model from {self.model_path}")

    def predict(self, data: dict) -> float:
        df = pd.DataFrame([data], columns=self.feature_columns)
        probability = self.model.predict_proba(df)[0, 1]
        return float(probability)