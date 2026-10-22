// Sanitized local-fixture aggregates only. No SQL values, credentials or PII.
const totals={queries:0,rowsRead:0,rowsWritten:0,byTable:{}};
export function recordProofCost(env,sql,result){
 if(env.LOCAL_PROOF_ONLY!=='true')return;
 const table=String(sql).match(/(?:\bFROM|\bINTO|\bUPDATE)\s+["`]?([a-z_]+)/i)?.[1]?.toLowerCase()??'other';
 const t=totals.byTable[table]??={queries:0,rowsRead:0,rowsWritten:0};
 for(const r of [totals,t]){r.queries++;r.rowsRead+=result.meta?.rows_read??0;r.rowsWritten+=result.meta?.rows_written??0;}
}
export const proofCostSummary=()=>structuredClone(totals);
