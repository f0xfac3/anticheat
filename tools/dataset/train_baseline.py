"""Offline research baseline. Requires reviewed exports; never installs a model in the server."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import joblib
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.impute import SimpleImputer
from sklearn.pipeline import make_pipeline
from sklearn.metrics import roc_auc_score, average_precision_score
from dataset_tool import FEATURES, file_hash


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("data",type=Path);p.add_argument("--module",required=True)
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--split",choices=["participants","run"],default="participants")
    p.add_argument("--seed",type=int,default=42);args=p.parse_args()
    if not args.data.with_suffix(args.data.suffix+".report.json").is_file():raise SystemExit("Missing completed export report")
    export=json.loads(args.data.with_suffix(args.data.suffix+".report.json").read_text())
    if export.get("feature_version")!=1 or export.get("features")!=FEATURES or export.get("data_sha256")!=file_hash(args.data):raise SystemExit("Export report does not match data/features; export again from reviewed raw recordings.")
    rows=[]
    with args.data.open() as stream:
        for line in stream:
            if line.strip():rows.append(json.loads(line))
            if len(rows)>200000:raise SystemExit("This in-memory pilot trainer is limited to 200,000 windows; shard/sample a documented subset for this baseline.")
    rows=[r for r in rows if r["label"]=="legit" or r["module"]==args.module]
    if len(rows)<100:raise SystemExit("Need at least 100 reviewed windows; no model trained.")
    # Participants who have fought each other are one connected group. This prevents
    # an opponent's behavior appearing in both a training and held-out feature window.
    parents={}
    def root(x):
        parents.setdefault(x,x)
        while parents[x]!=x:parents[x]=parents[parents[x]];x=parents[x]
        return x
    def union(a,b):parents[root(a)]=root(b)
    for r in rows:
        root(r["player_group"])
        for opponent in r["opponent_groups"]:union(r["player_group"],opponent)
    group=lambda r:r["run"] if args.split=="run" else root(r["player_group"])
    groups=sorted({group(r) for r in rows})
    if len(groups)<5:raise SystemExit("Need >=5 independent groups. Two testers are a pilot: collect >=5 balanced server runs and use --split run; this does not test new-player generalization.")
    rng=np.random.default_rng(args.seed);rng.shuffle(groups)
    n=max(1,len(groups)//5);test=set(groups[:n]);validation=set(groups[n:2*n]);train=set(groups[2*n:])
    selection={name:[r for r in rows if group(r) in part] for name,part in [("train",train),("validation",validation),("test",test)]}
    def matrix(part):
        return np.array([[r["features"].get(f) if r["features"].get(f) is not None else np.nan for f in FEATURES] for r in part]),np.array([r["label"]=="cheat" for r in part])
    for name,part in selection.items():
        _,y=matrix(part)
        if len(part)<20 or len(np.unique(y))!=2:raise SystemExit(f"{name} lacks both classes or sufficient windows. Collect balanced runs; do not search seeds for flattering scores.")
    model=make_pipeline(SimpleImputer(strategy="median",keep_empty_features=True),
        HistGradientBoostingClassifier(max_iter=100,max_leaf_nodes=7,min_samples_leaf=30,l2_regularization=5,random_state=args.seed))
    X,y=matrix(selection["train"]);model.fit(X,y)
    xv,yv=matrix(selection["validation"]);scores=model.predict_proba(xv)[:,1]
    # A conservative pilot threshold: no positive validation control windows. Zero
    # observed errors is not a statistical guarantee; the report includes exposure.
    threshold=float(np.nextafter(scores[~yv].max(),np.inf))
    report=dict(module=args.module,feature_version=1,features=FEATURES,threshold=threshold,
        evaluation_scope="known_testers_new_runs" if args.split=="run" else "held_out_participant_components",
        target="reviewed module-enabled windows, not individual packet violations",seed=args.seed,
        data_sha256=export["data_sha256"],groups={k:sorted(v) for k,v in [("train",train),("validation",validation),("test",test)]},metrics={})
    for name,part in selection.items():
        X,y=matrix(part);s=model.predict_proba(X)[:,1];positive=s>=threshold
        legit_hours=sum(r["seconds"] for r in part if r["label"]=="legit")/3600
        report["metrics"][name]=dict(windows=len(part),positive_windows=int(y.sum()),legit_hours=legit_hours,
            roc_auc=float(roc_auc_score(y,s)),average_precision=float(average_precision_score(y,s)),
            detected_positive_window_fraction=float(positive[y].mean()),
            false_positive_windows=int(positive[~y].sum()),
            false_positive_windows_per_hour=float(positive[~y].sum()/legit_hours))
    args.out.mkdir(parents=True,exist_ok=False)
    joblib.dump(model,args.out/"model.joblib")
    (args.out/"report.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))
    print("OFFLINE PILOT ONLY: no model deployed; window errors are not deduplicated alerts or ban rates.")


if __name__=="__main__":main()
