import json
import os
import pandas as pd
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import faithfulness, answer_relevancy, context_precision, context_recall
from langchain_groq import ChatGroq
from langchain_google_genai import GoogleGenerativeAIEmbeddings

def run_evaluation():
    # Load dataset
    with open('dataset.json', 'r') as f:
        data = json.load(f)
    
    # Format for Ragas
    ragas_dataset = {
        "question": [d["question"] for d in data],
        "answer": [d["answer"] for d in data],
        "contexts": [d["contexts"] for d in data],
        "ground_truth": [d["ground_truth"] for d in data]
    }
    dataset = Dataset.from_dict(ragas_dataset)
    
    # Setup Evaluator LLM (using Groq for speed/cost)
    groq_api_key = os.environ.get("GROQ_API_KEY")
    if not groq_api_key:
        print("GROQ_API_KEY environment variable is missing. Evaluation skipped.")
        return

    evaluator_llm = ChatGroq(model_name="llama3-70b-8192", groq_api_key=groq_api_key)
    
    gemini_api_key = os.environ.get("GEMINI_API_KEY")
    evaluator_embeddings = GoogleGenerativeAIEmbeddings(model="models/text-embedding-004", google_api_key=gemini_api_key) if gemini_api_key else None
    
    print("Running evaluation (this may take a minute)...")
    # Evaluate
    result = evaluate(
        dataset=dataset,
        metrics=[
            faithfulness,
            answer_relevancy,
            context_precision,
            context_recall
        ],
        llm=evaluator_llm,
        embeddings=evaluator_embeddings,
        raise_exceptions=False
    )
    
    print("\n--- Evaluation Results ---")
    print(result)
    print("--------------------------\n")
    
    # Export to pandas and save
    df = result.to_pandas()
    df.to_csv("eval_results.csv", index=False)
    print("Detailed results saved to eval_results.csv")

if __name__ == "__main__":
    run_evaluation()
