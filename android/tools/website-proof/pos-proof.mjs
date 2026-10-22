import {DatabaseSync} from "node:sqlite";
// Isolated protocol/SQLite simulator. Actual createBill is exercised by Android tests.
// This does not replace or modify the production Room schema/billing engine.
export class LocalPosProof {
  constructor(file,device){
    this.device=device;this.db=new DatabaseSync(file);
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
      CREATE TABLE IF NOT EXISTS claim_intent(order_id TEXT PRIMARY KEY,device TEXT NOT NULL,token TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS local_claim(order_id TEXT PRIMARY KEY,bill_id TEXT NOT NULL UNIQUE,device TEXT NOT NULL,token TEXT NOT NULL,revision INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS bills(bill_id TEXT PRIMARY KEY,order_id TEXT NOT NULL UNIQUE,total_paisa TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS stock(product_id TEXT PRIMARY KEY,quantity INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS payments(bill_id TEXT PRIMARY KEY REFERENCES bills(bill_id),amount_paisa TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS completion_outbox(order_id TEXT PRIMARY KEY REFERENCES local_claim(order_id),body TEXT NOT NULL,acked INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS inbox(order_id TEXT PRIMARY KEY,revision INTEGER NOT NULL,doc TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS cursor(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL);
      INSERT OR IGNORE INTO cursor VALUES(1,0);`);
  }
  beginClaim(orderId,claimToken){
    this.db.prepare("INSERT INTO claim_intent VALUES(?,?,?) ON CONFLICT(order_id) DO NOTHING").run(orderId,this.device,claimToken);
    const pending=this.db.prepare("SELECT * FROM claim_intent WHERE order_id=?").get(orderId);
    if(pending.device!==this.device)throw Error("Claim intent belongs to another installation");return pending.token;
  }
  rememberClaim(orderId,claim,claimToken){
    this.db.prepare("INSERT INTO local_claim VALUES(?,?,?,?,?) ON CONFLICT(order_id) DO NOTHING").run(orderId,claim.billId,this.device,claimToken,claim.revision);
    const row=this.db.prepare("SELECT * FROM local_claim WHERE order_id=?").get(orderId);
    if(row.bill_id!==claim.billId || row.device!==this.device || row.token!==claimToken)throw Error("Local claim mismatch");
  }
  commit(orderId,totalPaisa,lines,{failBeforeCommit=false}={}){
    this.db.exec("BEGIN IMMEDIATE");
    try{
      const claim=this.db.prepare("SELECT * FROM local_claim WHERE order_id=?").get(orderId);
      if(!claim || claim.device!==this.device)throw Error("No matching durable billing claim");
      const old=this.db.prepare("SELECT * FROM bills WHERE order_id=?").get(orderId);
      if(old){this.db.exec("COMMIT");return old.bill_id;}
      this.db.prepare("INSERT INTO bills VALUES(?,?,?)").run(claim.bill_id,orderId,totalPaisa);
      for(const line of lines){const changed=this.db.prepare("UPDATE stock SET quantity=quantity-? WHERE product_id=?").run(line.quantity,line.productId);
        if(changed.changes!==1)throw Error("Synthetic local product unavailable");}
      this.db.prepare("INSERT INTO payments VALUES(?,?)").run(claim.bill_id,totalPaisa);
      this.db.prepare("INSERT INTO completion_outbox(order_id,body) VALUES(?,?)").run(orderId,JSON.stringify({expectedRevision:claim.revision,claimToken:claim.token,billId:claim.bill_id}));
      if(failBeforeCommit)throw Error("Injected crash before local commit");
      this.db.exec("COMMIT");return claim.bill_id;
    }catch(e){this.db.exec("ROLLBACK");throw e;}
  }
  applyFeed(feed,{failBeforeCommit=false}={}){
    this.db.exec("BEGIN IMMEDIATE");
    try{
      for(const event of feed.events)this.db.prepare("INSERT INTO inbox VALUES(?,?,?) ON CONFLICT(order_id) DO UPDATE SET revision=excluded.revision,doc=excluded.doc WHERE excluded.revision>inbox.revision")
        .run(event.id,event.revision,JSON.stringify(event));
      this.db.prepare("UPDATE cursor SET value=MAX(value,?) WHERE id=1").run(feed.cursor);
      if(failBeforeCommit)throw Error("Injected interrupted sync");this.db.exec("COMMIT");
    }catch(e){this.db.exec("ROLLBACK");throw e;}
  }
  close(){this.db.close();}
}
