import { useState, useRef, useEffect, useCallback } from "react";

const API =
  import.meta.env.VITE_API_BASE ||
  "/api";

const css = `
  @import url('https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=Syne:wght@400;600;700;800&display=swap');
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  :root {
    --bg:       #0b0e14;
    --surface:  #12161f;
    --card:     #181d28;
    --border:   #242a38;
    --accent:   #00e5a0;
    --accent2:  #3b82f6;
    --supa:     #3ecf8e;
    --warn:     #f59e0b;
    --danger:   #ef4444;
    --text:     #e8ecf4;
    --muted:    #6b7694;
    --radius:   14px;
    --font-ui:  'Syne', sans-serif;
    --font-mono:'DM Mono', monospace;
  }
  body { background: var(--bg); color: var(--text); font-family: var(--font-ui); min-height: 100dvh; overscroll-behavior: none; }
  ::-webkit-scrollbar { width: 4px; }
  ::-webkit-scrollbar-track { background: var(--bg); }
  ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 99px; }
  .tabs { display: flex; border-bottom: 1px solid var(--border); background: var(--surface); position: sticky; top: 0; z-index: 40; }
  .tab { flex: 1; padding: 14px 0; font-family: var(--font-ui); font-size: 13px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; background: none; border: none; color: var(--muted); cursor: pointer; transition: color .2s; position: relative; }
  .tab.active { color: var(--accent); }
  .tab.active::after { content:''; position: absolute; bottom: -1px; left: 20%; right: 20%; height: 2px; background: var(--accent); border-radius: 99px; }
  .camera-wrap { position: relative; width: 100%; aspect-ratio: 3/4; background: #000; border-radius: var(--radius); overflow: hidden; }
  .scan-overlay { position: absolute; inset: 0; pointer-events: none; }
  .scan-corner { position: absolute; width: 28px; height: 28px; border-color: var(--accent); border-style: solid; border-width: 0; }
  .scan-corner.tl { top:18px;left:18px;border-top-width:3px;border-left-width:3px;border-radius:4px 0 0 0; }
  .scan-corner.tr { top:18px;right:18px;border-top-width:3px;border-right-width:3px;border-radius:0 4px 0 0; }
  .scan-corner.bl { bottom:18px;left:18px;border-bottom-width:3px;border-left-width:3px;border-radius:0 0 0 4px; }
  .scan-corner.br { bottom:18px;right:18px;border-bottom-width:3px;border-right-width:3px;border-radius:0 0 4px 0; }
  @keyframes scanline { 0%,100%{top:15%} 50%{top:80%} }
  .scan-line { position:absolute;left:10%;right:10%;height:2px;background:linear-gradient(90deg,transparent,var(--accent),transparent);animation:scanline 2.4s ease-in-out infinite;box-shadow:0 0 12px var(--accent); }
  .btn { display:inline-flex;align-items:center;justify-content:center;gap:8px;padding:12px 22px;border-radius:10px;font-family:var(--font-ui);font-size:14px;font-weight:700;border:none;cursor:pointer;transition:all .15s; }
  .btn-primary { background:var(--accent);color:#0b0e14; }
  .btn-primary:hover { filter:brightness(1.1); }
  .btn-primary:disabled { opacity:.4;cursor:not-allowed; }
  .btn-ghost { background:var(--card);color:var(--text);border:1px solid var(--border); }
  .btn-ghost:hover { background:var(--border); }
  .btn-danger { background:#2a1414;color:var(--danger);border:1px solid #3d1c1c; }
  .btn-danger:hover { background:#3d1c1c; }
  .btn-full { width:100%; }
  .shutter { width:68px;height:68px;border-radius:50%;background:#fff;border:4px solid rgba(255,255,255,.3);cursor:pointer;transition:transform .1s;box-shadow:0 0 0 0 rgba(0,229,160,.4); }
  .shutter:active { transform:scale(.93); }
  @keyframes pulse-ring { 0%{box-shadow:0 0 0 0 rgba(0,229,160,.5)} 70%{box-shadow:0 0 0 16px rgba(0,229,160,0)} 100%{box-shadow:0 0 0 0 rgba(0,229,160,0)} }
  .med-card { background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:16px;cursor:pointer;transition:border-color .2s; }
  .med-card:hover { border-color:var(--accent); }
  .badge { display:inline-block;padding:2px 9px;border-radius:6px;font-size:11px;font-weight:600;font-family:var(--font-mono); }
  .badge-high   { background:rgba(0,229,160,.12);color:var(--accent); }
  .badge-medium { background:rgba(245,158,11,.12);color:var(--warn); }
  .badge-low    { background:rgba(239,68,68,.12);color:var(--danger); }
  .badge-unknown{ background:rgba(107,118,148,.15);color:var(--muted); }
  .badge-supa   { background:rgba(62,207,142,.12);color:var(--supa); }
  .badge-sqlite { background:rgba(59,130,246,.12);color:var(--accent2); }
  .badge-sync-pending { background:rgba(245,158,11,.12);color:var(--warn); }
  .badge-sync-synced { background:rgba(62,207,142,.12);color:var(--supa); }
  .badge-sync-local { background:rgba(59,130,246,.12);color:var(--accent2); }
  .badge-sync-failed { background:rgba(239,68,68,.12);color:var(--danger); }
  .detail-row { display:flex;gap:12px;padding:10px 0;border-bottom:1px solid var(--border); }
  .detail-row:last-child { border-bottom:none; }
  .detail-label { font-size:11px;font-weight:700;letter-spacing:.07em;text-transform:uppercase;color:var(--muted);min-width:130px;padding-top:1px; }
  .detail-value { font-family:var(--font-mono);font-size:13px;color:var(--text);flex:1;word-break:break-word; }
  @keyframes spin { to { transform:rotate(360deg); } }
  .spinner { width:20px;height:20px;border:2px solid var(--border);border-top-color:var(--accent);border-radius:50%;animation:spin .7s linear infinite; }
  @keyframes slide-up { from{opacity:0;transform:translateY(16px)} to{opacity:1;transform:translateY(0)} }
  .toast { position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:var(--card);border:1px solid var(--border);border-radius:10px;padding:12px 20px;font-size:14px;z-index:100;animation:slide-up .25s ease;white-space:nowrap;max-width:90vw;text-align:center; }
  .toast.success { border-color:var(--accent);color:var(--accent); }
  .toast.error   { border-color:var(--danger);color:var(--danger); }
  .modal-bg { position:fixed;inset:0;background:rgba(0,0,0,.75);z-index:60;display:flex;align-items:flex-end; }
  .modal { background:var(--surface);border-radius:20px 20px 0 0;width:100%;max-height:88dvh;overflow-y:auto;padding:24px 20px 40px; }
  .modal-handle { width:36px;height:4px;background:var(--border);border-radius:99px;margin:0 auto 20px; }
  .dot { width:8px;height:8px;border-radius:50%;display:inline-block; }
  .dot-green  { background:var(--accent);box-shadow:0 0 6px var(--accent); }
  .dot-supa   { background:var(--supa);box-shadow:0 0 6px var(--supa); }
  .dot-red    { background:var(--danger); }
  .dot-yellow { background:var(--warn);box-shadow:0 0 6px var(--warn); }
  .section-title { font-size:11px;font-weight:800;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);margin-bottom:12px; }
  .empty-state { text-align:center;padding:60px 20px;color:var(--muted); }
  .empty-state .icon { font-size:48px;margin-bottom:12px; }
  input.search { width:100%;background:var(--card);border:1px solid var(--border);border-radius:10px;padding:12px 16px;color:var(--text);font-family:var(--font-ui);font-size:14px;outline:none;transition:border-color .2s; }
  input.search:focus { border-color:var(--accent); }
  input.search::placeholder { color:var(--muted); }
  .code-block { background:#080b10;border-radius:8px;padding:10px 14px;font-family:var(--font-mono);font-size:12px;color:#a0c4ff;overflow-x:auto;white-space:pre; }
  .status-row { display:flex;justify-content:space-between;padding:7px 0;border-bottom:1px solid var(--border);font-size:13px; }
  .status-row:last-child { border-bottom:none; }
`;

function confidenceBadge(c) {
  const map = { high:"badge-high", medium:"badge-medium", low:"badge-low" };
  return <span className={`badge ${map[c]||"badge-unknown"}`}>{c||"unknown"}</span>;
}
function syncBadge(status) {
  const map = {
    synced: "badge-sync-synced",
    pending_sync: "badge-sync-pending",
    local_only: "badge-sync-local",
    failed: "badge-sync-failed",
  };
  const label = {
    synced: "synced",
    pending_sync: "pending sync",
    local_only: "local only",
    failed: "sync failed",
  };
  return <span className={`badge ${map[status]||"badge-unknown"}`}>{label[status]||status||"unknown"}</span>;
}
function fmt(val) {
  if (!val || val==="null"||val==="None") return <span style={{color:"var(--muted)"}}>—</span>;
  return val;
}

function formatDateTime(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function Toast({msg,type}){ return <div className={`toast ${type}`}>{msg}</div>; }

function reviewFlag(meta){
  if(!meta?.needs_review) return null;
  return <span className="badge badge-medium" style={{marginLeft:8}}>review</span>;
}

const REVIEW_FIELDS = [
  ["brand_name","Brand Name"],
  ["generic_name","Generic Name"],
  ["manufacturer","Manufacturer"],
  ["dosage_form","Dosage Form"],
  ["strength","Strength"],
  ["quantity","Quantity"],
  ["active_ingredients","Active Ingredients"],
  ["batch_number","Batch / Lot No."],
  ["serial_number","Serial Number"],
  ["manufacture_date","Manufacture Date"],
  ["expiry_date","Expiry Date"],
  ["license_number","License / Reg. No."],
  ["barcode","Barcode / NDC"],
  ["indications","Indications"],
  ["warnings","Warnings"],
  ["storage_info","Storage Info"],
];

const PANEL_TYPES = [
  { value: "packet_date_side", label: "Packet Date Side" },
  { value: "packet_detail_side", label: "Packet Detail Side" },
  { value: "strip", label: "Strip" },
  { value: "other", label: "Other" },
];

function defaultPanelTypeForIndex(index) {
  if (index === 0) return "packet_date_side";
  if (index === 1) return "packet_detail_side";
  if (index === 2) return "strip";
  return "other";
}

function panelTypeLabel(value) {
  return PANEL_TYPES.find(panel => panel.value === value)?.label || "Other";
}

function cameraErrorMessage(error) {
  const secureContext = window.isSecureContext;
  const host = window.location.hostname;
  const isLanHttp = !secureContext && host !== "localhost" && host !== "127.0.0.1";

  if (!navigator.mediaDevices?.getUserMedia) {
    return "This browser does not support camera capture here. Use Upload instead.";
  }
  if (isLanHttp) {
    return "Mobile browsers usually require HTTPS for camera access on a LAN URL. Use Upload for now, or run the app over HTTPS.";
  }
  if (error?.name === "NotAllowedError" || error?.name === "PermissionDeniedError") {
    return "Camera permission was blocked by the browser or OS. Allow camera access, then try again.";
  }
  if (error?.name === "NotFoundError" || error?.name === "DevicesNotFoundError") {
    return "No camera was found on this device.";
  }
  if (error?.name === "NotReadableError" || error?.name === "TrackStartError") {
    return "The camera is busy or unavailable. Close other camera apps and try again.";
  }
  if (error?.name === "OverconstrainedError" || error?.name === "ConstraintNotSatisfiedError") {
    return "This device could not satisfy the requested camera settings. Try Upload instead.";
  }
  return "Camera could not be started here. Use Upload instead, or run the app over HTTPS on mobile.";
}

function MedicineModal({med,onClose,onDelete,onSaved,showToast}){
  const [view,setView]=useState("details");
  const [form,setForm]=useState(null);
  const [history,setHistory]=useState([]);
  const [historyLoading,setHistoryLoading]=useState(false);
  const [historyLoaded,setHistoryLoaded]=useState(false);
  const [historyError,setHistoryError]=useState(null);
  const [saving,setSaving]=useState(false);
  const [duplicates,setDuplicates]=useState(null);

  useEffect(()=>{
    if(!med) return;
    const nextForm = {};
    REVIEW_FIELDS.forEach(([key])=>{ nextForm[key] = med[key] || ""; });
    nextForm.scanned_at = med.scanned_at || "";
    nextForm.confidence = med.confidence || "";
    nextForm.note = "";
    setForm(nextForm);
    setDuplicates(null);
    setView("details");
    setHistory([]);
    setHistoryLoaded(false);
    setHistoryError(null);
  },[med]);

  useEffect(()=>{
    if(!med || historyLoaded || historyLoading || view!=="history") return;
    let active = true;
    setHistoryLoading(true);
    setHistoryError(null);
    fetch(`${API}/medicines/${med.id}/history`)
      .then(async res=>{
        if(!res.ok){
          const error = await res.json().catch(()=>({}));
          throw new Error(error.detail || "History load failed");
        }
        return res.json();
      })
      .then(data=>{
        if(!active) return;
        setHistory(data.history || []);
        setHistoryLoaded(true);
      })
      .catch(err=>{
        if(!active) return;
        setHistoryError(err.message);
      })
      .finally(()=>{
        if(active) setHistoryLoading(false);
      });
    return ()=>{ active = false; };
  },[historyLoaded,historyLoading,med,view]);

  if(!med || !form) return null;

  const fields=[
    ["Brand Name",med.brand_name],["Generic Name",med.generic_name],
    ["Manufacturer",med.manufacturer],["Dosage Form",med.dosage_form],
    ["Strength",med.strength],["Quantity",med.quantity],
    ["Active Ingredients",med.active_ingredients],["Batch / Lot No.",med.batch_number],
    ["Serial Number",med.serial_number],["Manufacture Date",med.manufacture_date],
    ["Expiry Date",med.expiry_date],["License / Reg. No.",med.license_number],
    ["Barcode / NDC",med.barcode],["Indications",med.indications],
    ["Warnings",med.warnings],["Storage",med.storage_info],
  ];

  const saveChanges = async()=>{
    setSaving(true);
    const payload = {};
    Object.entries(form).forEach(([key,value])=>{
      if(key==="note"){
        if(value.trim()) payload.note = value.trim();
        return;
      }
      const normalized = value.trim();
      const previous = med[key] || "";
      if(normalized !== previous){
        payload[key] = normalized || null;
      }
    });
    if(Object.keys(payload).length===0){
      showToast("No changes to save","error");
      setSaving(false);
      return;
    }
    try{
      const res = await fetch(`${API}/medicines/${med.id}`,{
        method:"PATCH",
        headers:{"Content-Type":"application/json"},
        body:JSON.stringify(payload),
      });
      const data = await res.json();
      if(!res.ok) throw new Error(data.detail || "Update failed");
      setDuplicates(data.duplicates || null);
      setHistory(current=>data.history_entry ? [data.history_entry,...current] : current);
      setHistoryLoaded(true);
      setForm(current=>({...current,note:""}));
      onSaved(data.record);
      showToast("Record updated");
      setView("details");
    }catch(err){
      showToast(err.message,"error");
    }finally{
      setSaving(false);
    }
  };

  return(
    <div className="modal-bg" onClick={onClose}>
      <div className="modal" onClick={e=>e.stopPropagation()}>
        <div className="modal-handle"/>
        <div style={{display:"flex",justifyContent:"space-between",alignItems:"flex-start",marginBottom:20}}>
          <div>
            <div style={{fontSize:20,fontWeight:800,lineHeight:1.2}}>{med.brand_name||"Unnamed Medicine"}</div>
            <div style={{fontSize:13,color:"var(--muted)",marginTop:4,fontFamily:"var(--font-mono)"}}>
              #{med.id} · {formatDateTime(med.scanned_at)} · {confidenceBadge(med.confidence)}
            </div>
          </div>
          <button className="btn btn-ghost" style={{padding:"8px 12px"}} onClick={onClose}>✕</button>
        </div>

        <div style={{display:"flex",gap:8,marginBottom:16}}>
          {[
            ["details","Details"],
            ["edit","Edit"],
            ["history","History"],
          ].map(([id,label])=>(
            <button
              key={id}
              className={`btn ${view===id ? "btn-primary" : "btn-ghost"}`}
              style={{flex:1,padding:"10px 12px",fontSize:12}}
              onClick={()=>setView(id)}
            >
              {label}
            </button>
          ))}
        </div>

        {view==="details"&&(
          <div style={{background:"var(--card)",borderRadius:"var(--radius)",padding:"4px 16px",marginBottom:20}}>
            <div className="detail-row">
              <div className="detail-label">Sync Status</div>
              <div className="detail-value">{syncBadge(med.sync_status)}</div>
            </div>
            <div className="detail-row">
              <div className="detail-label">Last Updated</div>
              <div className="detail-value">{fmt(formatDateTime(med.updated_at))}</div>
            </div>
            {fields.map(([label,value])=>(
              <div key={label} className="detail-row">
                <div className="detail-label">{label}</div>
                <div className="detail-value">{fmt(value)}</div>
              </div>
            ))}
          </div>
        )}

        {view==="edit"&&(
          <div style={{display:"flex",flexDirection:"column",gap:14,marginBottom:20}}>
            {duplicates?.has_possible_duplicates&&(
              <div style={{background:"rgba(59,130,246,.08)",border:"1px solid rgba(59,130,246,.24)",borderRadius:10,padding:"12px 14px"}}>
                <div style={{fontSize:11,fontWeight:800,letterSpacing:".08em",textTransform:"uppercase",color:"var(--accent2)",marginBottom:8}}>Possible Duplicates After Edit</div>
                <div style={{display:"flex",flexDirection:"column",gap:8}}>
                  {duplicates.candidates.map(candidate=>(
                    <div key={candidate.id} style={{fontSize:12}}>
                      #{candidate.id} · {candidate.brand_name || "Unnamed"} · score {candidate.score}
                    </div>
                  ))}
                </div>
              </div>
            )}
            <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:16}}>
              <div style={{display:"flex",flexDirection:"column",gap:12}}>
                {[
                  ["scanned_at","Scanned At"],
                  ...REVIEW_FIELDS,
                  ["confidence","Confidence"],
                ].map(([key,label])=>(
                  <label key={key} style={{display:"flex",flexDirection:"column",gap:6}}>
                    <span style={{fontSize:11,fontWeight:700,letterSpacing:".07em",textTransform:"uppercase",color:"var(--muted)"}}>{label}</span>
                    <input
                      className="search"
                      value={form[key] || ""}
                      onChange={e=>setForm(current=>({...current,[key]:e.target.value}))}
                      placeholder={`Enter ${label.toLowerCase()}`}
                    />
                  </label>
                ))}
                <label style={{display:"flex",flexDirection:"column",gap:6}}>
                  <span style={{fontSize:11,fontWeight:700,letterSpacing:".07em",textTransform:"uppercase",color:"var(--muted)"}}>Audit Note</span>
                  <textarea
                    className="search"
                    rows={3}
                    value={form.note || ""}
                    onChange={e=>setForm(current=>({...current,note:e.target.value}))}
                    placeholder="Why are you changing this record?"
                    style={{resize:"vertical",minHeight:88}}
                  />
                </label>
              </div>
            </div>
            <button className="btn btn-primary btn-full" onClick={saveChanges} disabled={saving}>
              {saving ? "Saving Changes..." : "Save Changes"}
            </button>
          </div>
        )}

        {view==="history"&&(
          <div style={{display:"flex",flexDirection:"column",gap:12,marginBottom:20}}>
            {historyLoading&&<div style={{display:"flex",justifyContent:"center",padding:20}}><div className="spinner"/></div>}
            {historyError&&<div style={{fontSize:13,color:"var(--danger)"}}>⚠ {historyError}</div>}
            {!historyLoading&&history.length===0&&(
              <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:16,fontSize:13,color:"var(--muted)"}}>
                No audit entries yet.
              </div>
            )}
            {history.map(entry=>(
              <div key={entry.id} style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:16}}>
                <div style={{display:"flex",justifyContent:"space-between",gap:10,marginBottom:8}}>
                  <div style={{fontWeight:800,textTransform:"capitalize"}}>{entry.action}</div>
                  <div style={{fontSize:11,color:"var(--muted)",fontFamily:"var(--font-mono)"}}>{formatDateTime(entry.created_at)}</div>
                </div>
                {entry.note&&<div style={{fontSize:12,color:"var(--text)",marginBottom:10}}>{entry.note}</div>}
                {entry.changed_fields && Object.keys(entry.changed_fields).length>0 && (
                  <div style={{display:"flex",flexDirection:"column",gap:8}}>
                    {Object.entries(entry.changed_fields).slice(0,8).map(([field,change])=>(
                      <div key={field} style={{fontSize:11,fontFamily:"var(--font-mono)",color:"var(--muted)"}}>
                        <strong style={{color:"var(--text)"}}>{field}</strong>: {String(change.from ?? "—")} → {String(change.to ?? "—")}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        <button className="btn btn-danger btn-full" onClick={()=>onDelete(med.id)}>🗑 Delete Record</button>
      </div>
    </div>
  );
}

// ── Scanner ───────────────────────────────────────────────────────────────────
function ScannerTab({onScanned}){
  const videoRef=useRef(null), canvasRef=useRef(null), streamRef=useRef(null), fileRef=useRef(null);
  const [mode,setMode]=useState("idle");
  const [captures,setCaptures]=useState([]);
  const [selectedCapture,setSelectedCapture]=useState(0);
  const [result,setResult]=useState(null);
  const [draft,setDraft]=useState(null);
  const [ocr,setOcr]=useState(null);
  const [extractionInfo,setExtractionInfo]=useState(null);
  const [fieldMeta,setFieldMeta]=useState({});
  const [reviewHints,setReviewHints]=useState([]);
  const [duplicates,setDuplicates]=useState(null);
  const [error,setError]=useState(null);

  const relabelCaptures=useCallback(items=>(
    items.map((item,index)=>({
      ...item,
      panel_name: item.panel_name || `Panel ${index+1}`,
      panel_type: item.panel_type || defaultPanelTypeForIndex(index),
    }))
  ),[]);

  const stopStream=useCallback(()=>{
    if(streamRef.current){streamRef.current.getTracks().forEach(t=>t.stop());streamRef.current=null;}
  },[]);

  const startCamera=useCallback(async()=>{
    setError(null);
    try{
      const stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:"environment"},width:{ideal:1920},height:{ideal:1080}},audio:false});
      streamRef.current=stream;
      if(videoRef.current) videoRef.current.srcObject=stream;
      setMode("live");
    }catch(e){setError(cameraErrorMessage(e));}
  },[]);

  const capture=useCallback(()=>{
    const v=videoRef.current,c=canvasRef.current;
    if(!v||!c) return;
    c.width=v.videoWidth;c.height=v.videoHeight;
    c.getContext("2d").drawImage(v,0,0);
    setCaptures(current=>{
      const next=relabelCaptures([...current,{
        image_base64:c.toDataURL("image/jpeg",0.92),
        image_filename:`capture-${current.length+1}.jpg`,
        panel_name:`Panel ${current.length+1}`,
        panel_type:defaultPanelTypeForIndex(current.length),
      }]);
      setSelectedCapture(next.length-1);
      return next;
    });
    stopStream();setMode("preview");
  },[relabelCaptures,stopStream]);

  const handleFile=useCallback(e=>{
    const files=Array.from(e.target.files||[]);
    if(!files.length) return;
    Promise.all(files.map((file,index)=>new Promise(resolve=>{
      const r=new FileReader();
      r.onload=ev=>resolve({
        image_base64:ev.target.result,
        image_filename:file.name,
        panel_name:`Panel ${index+1}`,
        panel_type:defaultPanelTypeForIndex(index),
      });
      r.readAsDataURL(file);
    }))).then(items=>{
      setCaptures(current=>{
        const next=relabelCaptures([...current,...items.map((item,index)=>({
          ...item,
          panel_name:`Panel ${current.length+index+1}`,
          panel_type:defaultPanelTypeForIndex(current.length+index),
        }))]);
        setSelectedCapture(next.length-1);
        return next;
      });
      setMode("preview");
    });
  },[relabelCaptures]);

  const doScan=useCallback(async()=>{
    if(!captures.length) return;
    setMode("scanning");setError(null);
    try{
      const res=await fetch(`${API}/scan`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({images:captures})});
      if(!res.ok){const e=await res.json();throw new Error(e.detail||"Scan failed");}
      const data=await res.json();
      setDraft(data.draft);setOcr(data.ocr);setFieldMeta(data.field_meta||{});setReviewHints(data.review_hints||[]);setDuplicates(data.duplicates||null);setExtractionInfo({provider:data.extraction_provider,mode:data.extraction});setMode("review");
    }catch(e){setError(e.message);setMode("preview");}
  },[captures]);

  const saveDraft=useCallback(async()=>{
    if(!draft) return;
    setMode("saving");setError(null);
    try{
      const res=await fetch(`${API}/medicines`,{
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body:JSON.stringify(draft)
      });
      if(!res.ok){const e=await res.json();throw new Error(e.detail||"Save failed");}
      const data=await res.json();
      setDuplicates(data.duplicates||null);
      setResult(data);setMode("done");onScanned();
    }catch(e){setError(e.message);setMode("review");}
  },[draft,onScanned]);

  const reset=useCallback(()=>{
    stopStream();setCaptures([]);setSelectedCapture(0);setResult(null);setDraft(null);setOcr(null);setExtractionInfo(null);setFieldMeta({});setReviewHints([]);setDuplicates(null);setError(null);setMode("idle");
  },[stopStream]);

  const removeCapture=useCallback(index=>{
    setCaptures(current=>{
      const next=relabelCaptures(current.filter((_,i)=>i!==index).map((item,i)=>({...item,panel_name:`Panel ${i+1}`})));
      setSelectedCapture(next.length ? Math.min(index,next.length-1) : 0);
      if(!next.length) setMode("idle");
      return next;
    });
  },[relabelCaptures]);

  const updateCapturePanelType=useCallback((index,panelType)=>{
    setCaptures(current=>current.map((item,i)=>i===index?{...item,panel_type:panelType}:item));
  },[]);

  useEffect(()=>()=>stopStream(),[stopStream]);

  const activePreview=captures[selectedCapture]?.image_base64;

  return(
    <div style={{padding:"20px 16px",maxWidth:480,margin:"0 auto"}}>
      <div className="camera-wrap" style={{marginBottom:20}}>
        {mode==="live"&&(<>
          <video ref={videoRef} autoPlay playsInline muted style={{width:"100%",height:"100%",objectFit:"cover"}}/>
          <div className="scan-overlay">
            <div className="scan-corner tl"/><div className="scan-corner tr"/>
            <div className="scan-corner bl"/><div className="scan-corner br"/>
            <div className="scan-line"/>
          </div>
        </>)}
        {(mode==="preview"||mode==="scanning"||mode==="review"||mode==="saving"||mode==="done")&&activePreview&&(
          <img src={activePreview} alt="captured" style={{width:"100%",height:"100%",objectFit:"cover"}}/>
        )}
        {mode==="idle"&&(
          <div style={{display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",height:"100%",gap:8,color:"var(--muted)"}}>
            <div style={{fontSize:48}}>💊</div>
            <div style={{fontWeight:700,fontSize:15}}>No image yet</div>
            <div style={{fontSize:13}}>Open camera or upload photo</div>
          </div>
        )}
        {(mode==="scanning"||mode==="saving")&&(
          <div style={{position:"absolute",inset:0,background:"rgba(0,0,0,.55)",display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",gap:14}}>
            <div className="spinner" style={{width:36,height:36,borderWidth:3}}/>
            <div style={{fontWeight:700,color:"var(--accent)"}}>{mode==="scanning"?"Extracting…":"Saving…"}</div>
            <div style={{fontSize:12,color:"var(--muted)"}}>{mode==="scanning"?"OCR and LLaVA are analysing the package":"Writing the reviewed record locally"}</div>
          </div>
        )}
      </div>
      <canvas ref={canvasRef} style={{display:"none"}}/>
      {captures.length>0&&(
        <div style={{display:"flex",gap:10,overflowX:"auto",paddingBottom:8,marginBottom:16}}>
          {captures.map((capture,index)=>(
            <div key={`${capture.panel_name}-${index}`} style={{minWidth:84}}>
              <button onClick={()=>setSelectedCapture(index)} style={{display:"block",width:84,height:84,padding:0,border:index===selectedCapture?"2px solid var(--accent)":"1px solid var(--border)",borderRadius:10,overflow:"hidden",background:"var(--card)",marginBottom:6,cursor:"pointer"}}>
                <img src={capture.image_base64} alt={capture.panel_name} style={{width:"100%",height:"100%",objectFit:"cover"}}/>
              </button>
              <div style={{fontSize:10,color:"var(--muted)",marginBottom:4,textAlign:"center"}}>{capture.panel_name}</div>
              <select
                className="search"
                value={capture.panel_type || "other"}
                onChange={e=>updateCapturePanelType(index,e.target.value)}
                style={{padding:"7px 8px",fontSize:11,marginBottom:6}}
              >
                {PANEL_TYPES.map(option=>(
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
              <button className="btn btn-danger" style={{width:"100%",padding:"6px 8px",fontSize:11}} onClick={()=>removeCapture(index)}>Remove</button>
            </div>
          ))}
        </div>
      )}
      {error&&(
        <div style={{background:"rgba(239,68,68,.1)",border:"1px solid rgba(239,68,68,.3)",borderRadius:10,padding:"12px 16px",marginBottom:16,fontSize:13,color:"var(--danger)"}}>⚠ {error}</div>
      )}
      {mode==="idle"&&(
        <div style={{display:"flex",gap:10}}>
          <button className="btn btn-primary" style={{flex:1}} onClick={startCamera}>📷 Open Camera</button>
          <button className="btn btn-ghost" onClick={()=>fileRef.current.click()}>🗂 Upload</button>
          <input ref={fileRef} type="file" accept="image/*" style={{display:"none"}} onChange={handleFile}/>
        </div>
      )}
      {mode==="live"&&(
        <div style={{display:"flex",justifyContent:"center",alignItems:"center",gap:20}}>
          <button className="btn btn-ghost" onClick={reset}>Cancel</button>
          <button className="shutter" onClick={capture}/>
          <button className="btn btn-ghost" onClick={()=>fileRef.current.click()}>Upload</button>
          <input ref={fileRef} type="file" accept="image/*" style={{display:"none"}} onChange={handleFile}/>
        </div>
      )}
      {mode==="preview"&&(
        <div style={{display:"flex",flexDirection:"column",gap:10}}>
          <div style={{fontSize:12,color:"var(--muted)"}}>
            Captured panels: {captures.length}. Tag each image as packet date side, packet detail side, or strip so dates come from the packet and brand/strength can prefer the strip.
          </div>
          <div style={{display:"flex",gap:10}}>
            <button className="btn btn-ghost" style={{flex:1}} onClick={startCamera}>📷 Add Camera Shot</button>
            <button className="btn btn-ghost" style={{flex:1}} onClick={()=>fileRef.current.click()}>🗂 Add Uploads</button>
            <input ref={fileRef} type="file" accept="image/*" multiple style={{display:"none"}} onChange={handleFile}/>
          </div>
          <div style={{display:"flex",gap:10}}>
            <button className="btn btn-ghost" style={{flex:1}} onClick={reset}>Clear All</button>
            <button className="btn btn-primary" style={{flex:2}} onClick={doScan}>🔍 Extract Draft</button>
          </div>
        </div>
      )}
      {mode==="review"&&draft&&(
        <div style={{display:"flex",flexDirection:"column",gap:14}}>
          <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:16}}>
            <div style={{fontWeight:800,fontSize:15,marginBottom:6}}>Review Before Save</div>
            <div style={{fontSize:12,color:"var(--muted)",marginBottom:14}}>
              OCR is useful for non-barcoded medicine packages, but batch numbers, expiry dates, and manufacturer text should still be checked before saving.
            </div>
            {extractionInfo&&(
              <div style={{fontSize:12,color:"var(--muted)",marginBottom:14}}>
                Extraction mode: <strong>{extractionInfo.mode?.app_mode || "unknown"}</strong> · provider used: <strong>{extractionInfo.provider || "unknown"}</strong>
              </div>
            )}
            {reviewHints.length>0&&(
              <div style={{background:"rgba(245,158,11,.08)",border:"1px solid rgba(245,158,11,.24)",borderRadius:10,padding:"12px 14px",marginBottom:14}}>
                <div style={{fontSize:11,fontWeight:800,letterSpacing:".08em",textTransform:"uppercase",color:"var(--warn)",marginBottom:8}}>Review Hints</div>
                <div style={{display:"flex",flexDirection:"column",gap:6,fontSize:12,color:"var(--text)"}}>
                  {reviewHints.map((hint,index)=><div key={index}>{hint}</div>)}
                </div>
              </div>
            )}
            {duplicates?.has_possible_duplicates&&(
              <div style={{background:"rgba(59,130,246,.08)",border:"1px solid rgba(59,130,246,.24)",borderRadius:10,padding:"12px 14px",marginBottom:14}}>
                <div style={{fontSize:11,fontWeight:800,letterSpacing:".08em",textTransform:"uppercase",color:"var(--accent2)",marginBottom:8}}>Possible Duplicates</div>
                <div style={{display:"flex",flexDirection:"column",gap:10}}>
                  {duplicates.candidates.map(candidate=>(
                    <div key={candidate.id} style={{borderBottom:"1px solid var(--border)",paddingBottom:10}}>
                      <div style={{fontSize:13,fontWeight:700}}>{candidate.brand_name || "Unnamed"} <span style={{color:"var(--muted)",fontWeight:400}}>{candidate.strength ? `· ${candidate.strength}` : ""}</span></div>
                      <div style={{fontSize:11,color:"var(--muted)",fontFamily:"var(--font-mono)",marginTop:4}}>
                        #{candidate.id} · {candidate.batch_number || "no batch"} · {candidate.expiry_date || "no expiry"}
                      </div>
                      <div style={{fontSize:11,color:"var(--accent2)",marginTop:4}}>
                        score {candidate.score} · {candidate.reasons.join(", ")}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
            <div style={{display:"flex",flexDirection:"column",gap:12}}>
              {REVIEW_FIELDS.map(([key,label])=>(
                <label key={key} style={{display:"flex",flexDirection:"column",gap:6}}>
                  <span style={{fontSize:11,fontWeight:700,letterSpacing:".07em",textTransform:"uppercase",color:fieldMeta[key]?.needs_review?"var(--warn)":"var(--muted)"}}>{label}{reviewFlag(fieldMeta[key])}</span>
                  <input
                    className="search"
                    value={draft[key]||""}
                    onChange={e=>setDraft(current=>({...current,[key]:e.target.value}))}
                    placeholder={`Enter ${label.toLowerCase()}`}
                  />
                  {fieldMeta[key]?.evidence&&(
                    <span style={{fontSize:11,color:"var(--muted)",fontFamily:"var(--font-mono)"}}>evidence: {fieldMeta[key].evidence}</span>
                  )}
                  {fieldMeta[key]?.source_panel_name&&(
                    <span style={{fontSize:11,color:"var(--accent2)",fontFamily:"var(--font-mono)"}}>
                      source: {fieldMeta[key].source_panel_name} · {panelTypeLabel(fieldMeta[key].source_panel_type)}
                    </span>
                  )}
                </label>
              ))}
            </div>
          </div>

          <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:16}}>
            <div style={{fontWeight:800,fontSize:14,marginBottom:8}}>OCR Text</div>
            <div className="code-block" style={{whiteSpace:"pre-wrap"}}>{ocr?.text || "No OCR text available"}</div>
          </div>

          {ocr?.preprocessing?.length>0&&(
            <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:16}}>
              <div style={{fontWeight:800,fontSize:14,marginBottom:10}}>Preprocessing</div>
              <div style={{display:"flex",flexDirection:"column",gap:10}}>
                {ocr.preprocessing.map((item,index)=>(
                  <div key={`${item.panel_name}-${index}`} style={{paddingBottom:10,borderBottom:index===ocr.preprocessing.length-1?"none":"1px solid var(--border)"}}>
                    <div style={{fontSize:12,fontWeight:700,marginBottom:4}}>
                      {item.panel_name} <span style={{color:"var(--muted)",fontWeight:400}}>· {panelTypeLabel(item.panel_type)}</span>
                    </div>
                    <div style={{fontSize:11,color:"var(--muted)",fontFamily:"var(--font-mono)"}}>
                      {`${item.original_size?.width}x${item.original_size?.height} -> ${item.processed_size?.width}x${item.processed_size?.height}`}
                    </div>
                    <div style={{fontSize:11,color:"var(--muted)",marginTop:4}}>
                      steps: {(item.steps||[]).join(", ")}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div style={{display:"flex",gap:10}}>
            <button className="btn btn-ghost" style={{flex:1}} onClick={reset}>Retake</button>
            <button className="btn btn-primary" style={{flex:2}} onClick={saveDraft}>💾 Save Reviewed Record</button>
          </div>
        </div>
      )}
      {mode==="done"&&result&&(
        <div style={{marginTop:4}}>
          <div style={{background:"rgba(0,229,160,.07)",border:"1px solid rgba(0,229,160,.25)",borderRadius:"var(--radius)",padding:16,marginBottom:16}}>
            <div style={{fontWeight:800,color:"var(--accent)",marginBottom:10,fontSize:15}}>✓ Saved to database #{result.id}</div>
            {[["Brand",result.record?.brand_name],["Generic",result.record?.generic_name],["Strength",result.record?.strength],["Expiry",result.record?.expiry_date],["Confidence",result.record?.confidence],["Sync",result.record?.sync_status?.replace("_"," ")]].filter(([,v])=>v).map(([k,v])=>(
              <div key={k} style={{display:"flex",gap:10,marginBottom:4}}>
                <span style={{fontSize:11,color:"var(--muted)",minWidth:70,fontWeight:700,textTransform:"uppercase",letterSpacing:".05em"}}>{k}</span>
                <span style={{fontFamily:"var(--font-mono)",fontSize:13}}>{k==="Sync" ? syncBadge(result.record?.sync_status) : v}</span>
              </div>
            ))}
          </div>
          <button className="btn btn-primary btn-full" onClick={reset}>Scan Another</button>
        </div>
      )}
    </div>
  );
}

// ── Database Tab ──────────────────────────────────────────────────────────────
function DatabaseTab(){
  const [medicines,setMedicines]=useState([]);
  const [total,setTotal]=useState(0);
  const [search,setSearch]=useState("");
  const [loading,setLoading]=useState(false);
  const [selected,setSelected]=useState(null);
  const [toast,setToast]=useState(null);

  const showToast=(msg,type="success")=>{setToast({msg,type});setTimeout(()=>setToast(null),2800);};

  const load=useCallback(async(q="")=>{
    setLoading(true);
    try{
      const res=await fetch(`${API}/medicines?limit=50&search=${encodeURIComponent(q)}`);
      const data=await res.json();
      setMedicines(data.medicines||[]);setTotal(data.total||0);
    }catch{showToast("Cannot reach backend","error");}
    finally{setLoading(false);}
  },[]);

  useEffect(()=>{load();},[load]);
  useEffect(()=>{const t=setTimeout(()=>load(search),350);return()=>clearTimeout(t);},[search,load]);

  const handleDelete=async id=>{
    try{
      const res = await fetch(`${API}/medicines/${id}`,{method:"DELETE"});
      const data = await res.json().catch(()=>({}));
      if(!res.ok) throw new Error(data.detail || "Delete failed");
      setSelected(null);
      showToast("Record deleted");
      load(search);
    }catch(err){
      showToast(err.message,"error");
    }
  };

  const handleSaved = updatedRecord => {
    setSelected(updatedRecord);
    setMedicines(current=>current.map(item=>item.id===updatedRecord.id ? updatedRecord : item));
  };

  const expiryColor=d=>{
    if(!d) return "";
    const diff=(new Date(d)-new Date())/(1000*60*60*24);
    if(isNaN(diff)) return "";
    return diff<0?"var(--danger)":diff<90?"var(--warn)":"var(--accent)";
  };

  return(
    <div style={{padding:"20px 16px",maxWidth:480,margin:"0 auto"}}>
      <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",marginBottom:16}}>
        <div style={{fontWeight:800,fontSize:18}}>Records <span style={{color:"var(--muted)",fontWeight:400,fontSize:14}}>({total})</span></div>
        <button className="btn btn-ghost" style={{padding:"8px 12px",fontSize:12}} onClick={()=>load(search)}>↺ Refresh</button>
      </div>
      <input className="search" placeholder="Search brand, generic, manufacturer…" value={search} onChange={e=>setSearch(e.target.value)} style={{marginBottom:16}}/>
      {loading&&<div style={{display:"flex",justifyContent:"center",padding:40}}><div className="spinner"/></div>}
      {!loading&&medicines.length===0&&(
        <div className="empty-state"><div className="icon">🏥</div><div style={{fontWeight:700,marginBottom:6}}>No medicines yet</div><div style={{fontSize:13}}>Scan a medicine package to get started</div></div>
      )}
      <div style={{display:"flex",flexDirection:"column",gap:10}}>
        {medicines.map(med=>(
          <div key={med.id} className="med-card" onClick={()=>setSelected(med)}>
            <div style={{display:"flex",justifyContent:"space-between",alignItems:"flex-start"}}>
              <div style={{flex:1}}>
                <div style={{fontWeight:700,fontSize:15,marginBottom:2}}>{med.brand_name||<span style={{color:"var(--muted)"}}>Unnamed</span>}</div>
                <div style={{fontSize:12,color:"var(--muted)",fontFamily:"var(--font-mono)",marginBottom:8}}>
                  {med.generic_name||"—"}{med.strength?` · ${med.strength}`:""}
                </div>
                <div style={{display:"flex",gap:8,flexWrap:"wrap",alignItems:"center"}}>
                  {confidenceBadge(med.confidence)}
                  {med.expiry_date&&<span style={{fontSize:11,fontFamily:"var(--font-mono)",color:expiryColor(med.expiry_date)}}>exp {med.expiry_date}</span>}
                </div>
              </div>
              <div style={{fontSize:11,color:"var(--muted)",textAlign:"right",fontFamily:"var(--font-mono)",minWidth:60}}>
                #{med.id}<br/>{new Date(med.scanned_at).toLocaleDateString()}
              </div>
            </div>
          </div>
        ))}
      </div>
      {selected&&(
        <MedicineModal
          med={selected}
          onClose={()=>setSelected(null)}
          onDelete={handleDelete}
          onSaved={handleSaved}
          showToast={showToast}
        />
      )}
      {toast&&<Toast msg={toast.msg} type={toast.type}/>}
    </div>
  );
}

// ── Status Tab ────────────────────────────────────────────────────────────────
function StatusTab(){
  const [health,setHealth]=useState(null);
  const [syncStatus,setSyncStatus]=useState(null);
  const [loading,setLoading]=useState(true);
  const [syncing,setSyncing]=useState(false);
  const [portabilityBusy,setPortabilityBusy]=useState("");
  const [importText,setImportText]=useState("");
  const [portabilityToast,setPortabilityToast]=useState(null);
  const importFileRef=useRef(null);
  const restoreFileRef=useRef(null);

  const showPortabilityToast=(msg,type="success")=>{
    setPortabilityToast({msg,type});
    setTimeout(()=>setPortabilityToast(null),2800);
  };

  const check=useCallback(async()=>{
    setLoading(true);
    try{
      const [healthResponse,syncResponse]=await Promise.all([
        fetch(`${API}/health`),
        fetch(`${API}/sync/status`),
      ]);
      setHealth(await healthResponse.json());
      setSyncStatus(await syncResponse.json());
    }
    catch{setHealth({status:"error",ollama:false,available_models:[],supabase_configured:false,supabase_connected:false,db_mode:"unknown"});}
    finally{setLoading(false);}
  },[]);

  useEffect(()=>{check();},[check]);

  const isSupabase = !!health?.supabase_configured;
  const extractionMode = health?.extraction?.app_mode || "unknown";
  const extractionStrategy = health?.extraction?.strategy || "unknown";
  const hasModel   = health?.available_models?.some(m=>m.startsWith("llava"));
  const localExtractorReady = !health?.local_extraction_enabled || (health?.ollama && hasModel);
  const cloudReady = !health?.cloud_extraction_configured || health?.cloud_extraction_connected;
  const allOk      = health?.status==="ok" && health?.ocr && localExtractorReady && cloudReady;

  const SUPABASE_SQL = `-- Run this in Supabase SQL Editor
create table if not exists medicines (
  id               bigint generated always as identity primary key,
  local_id         bigint unique not null,
  scanned_at       timestamptz not null default now(),
  brand_name       text,
  generic_name     text,
  manufacturer     text,
  batch_number     text,
  serial_number    text,
  dosage_form      text,
  strength         text,
  quantity         text,
  manufacture_date text,
  expiry_date      text,
  license_number   text,
  barcode          text,
  indications      text,
  warnings         text,
  storage_info     text,
  active_ingredients text,
  raw_extracted    text,
  confidence       text,
  image_filename   text,
  image_path       text,
  local_updated_at timestamptz,
  last_synced_at   timestamptz
);

-- Allow your local backend to sync records
alter table medicines disable row level security;`;

  const ENV_EXAMPLE = `# apps/local-backend/.env
APP_MODE=hybrid
CLOUD_EXTRACT_URL=http://localhost:8100/extract
SUPABASE_URL=https://xxxx.supabase.co
SUPABASE_KEY=eyJhbGci...  ← service_role key`;

  const runSync=useCallback(async()=>{
    setSyncing(true);
    try{
      const r=await fetch(`${API}/sync/run`,{method:"POST"});
      const data=await r.json();
      setHealth(prev=>prev ? {...prev,sync:data.sync,supabase_connected:true} : prev);
      const syncResponse=await fetch(`${API}/sync/status`);
      setSyncStatus(await syncResponse.json());
    }finally{setSyncing(false);}
  },[]);

  const downloadExport=useCallback(async(kind)=>{
    setPortabilityBusy(`export-${kind}`);
    try{
      const res=await fetch(`${API}/export/${kind}`);
      if(!res.ok) throw new Error("Export failed");
      const blob=await res.blob();
      const url=window.URL.createObjectURL(blob);
      const a=document.createElement("a");
      a.href=url;
      a.download=`mediscan-export.${kind==="csv"?"csv":"json"}`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
      showPortabilityToast(`${kind.toUpperCase()} export downloaded`);
    }catch(err){
      showPortabilityToast(err.message,"error");
    }finally{
      setPortabilityBusy("");
    }
  },[]);

  const createBackup=useCallback(async()=>{
    setPortabilityBusy("backup");
    try{
      const res=await fetch(`${API}/backup/create`,{method:"POST"});
      const data=await res.json();
      if(!res.ok) throw new Error(data.detail || "Backup failed");
      showPortabilityToast(`Backup created at ${data.path}`);
    }catch(err){
      showPortabilityToast(err.message,"error");
    }finally{
      setPortabilityBusy("");
    }
  },[]);

  const createBundleBackup=useCallback(async()=>{
    setPortabilityBusy("bundle");
    try{
      const res=await fetch(`${API}/backup/bundle`,{method:"POST"});
      const data=await res.json();
      if(!res.ok) throw new Error(data.detail || "Bundle backup failed");
      showPortabilityToast(`Bundle backup created at ${data.path}`);
    }catch(err){
      showPortabilityToast(err.message,"error");
    }finally{
      setPortabilityBusy("");
    }
  },[]);

  const handleRestoreFile=useCallback(async e=>{
    const file=e.target.files?.[0];
    if(!file) return;
    const reader = new FileReader();
    reader.onload = async event => {
      setPortabilityBusy("restore");
      try{
        const res=await fetch(`${API}/backup/restore`,{
          method:"POST",
          headers:{"Content-Type":"application/json"},
          body:JSON.stringify({
            archive_base64:event.target?.result,
            archive_filename:file.name,
          }),
        });
        const data=await res.json();
        if(!res.ok) throw new Error(data.detail || "Restore failed");
        showPortabilityToast(`Bundle restored: ${data.records} records, ${data.scan_files} scans`);
        await check();
      }catch(err){
        showPortabilityToast(err.message,"error");
      }finally{
        setPortabilityBusy("");
      }
    };
    reader.readAsDataURL(file);
  },[check]);

  const importJson=useCallback(async()=>{
    let parsed;
    try{
      parsed=JSON.parse(importText || "{}");
    }catch{
      showPortabilityToast("Import JSON is invalid","error");
      return;
    }
    const records = Array.isArray(parsed) ? parsed : parsed.records;
    if(!Array.isArray(records) || !records.length){
      showPortabilityToast("Import JSON must contain a non-empty records array","error");
      return;
    }

    setPortabilityBusy("import");
    try{
      const res=await fetch(`${API}/import/json`,{
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body:JSON.stringify({
          records,
          skip_possible_duplicates:true,
          note:"Imported from Status tab",
        }),
      });
      const data=await res.json();
      if(!res.ok) throw new Error(data.detail || "Import failed");
      setHealth(prev=>prev ? {...prev,sync:data.sync || prev.sync} : prev);
      showPortabilityToast(`Imported ${data.imported_count} record(s), skipped ${data.skipped_count}`);
    }catch(err){
      showPortabilityToast(err.message,"error");
    }finally{
      setPortabilityBusy("");
    }
  },[importText]);

  const handleImportFile=useCallback(async e=>{
    const file=e.target.files?.[0];
    if(!file) return;
    const text=await file.text();
    setImportText(text);
    showPortabilityToast(`Loaded ${file.name}`);
  },[]);

  return(
    <div style={{padding:"20px 16px",maxWidth:480,margin:"0 auto"}}>
      <div style={{fontWeight:800,fontSize:18,marginBottom:20}}>System Status</div>

      {/* Summary card */}
      <div style={{background:"var(--card)",border:`1px solid ${allOk?"rgba(0,229,160,.3)":"rgba(245,158,11,.3)"}`,borderRadius:"var(--radius)",padding:16,marginBottom:24}}>
        <div style={{display:"flex",alignItems:"center",gap:10,marginBottom:14}}>
          <span className={`dot ${allOk?"dot-green":"dot-yellow"}`}/>
          <span style={{fontWeight:700}}>{allOk?"Ready to scan locally":"Setup required"}</span>
        </div>

        {[
          ["Backend API",       health?.status==="ok"],
          ["Local OCR",         health?.ocr],
          ["Ollama runtime",    health?.local_extraction_enabled ? health?.ollama : true],
          ["LLaVA model",       health?.local_extraction_enabled ? hasModel : true],
          ["Cloud extractor",   health?.cloud_extraction_configured ? health?.cloud_extraction_connected : true],
        ].map(([label,ok])=>(
          <div key={label} className="status-row">
            <span style={{color:"var(--muted)"}}>{label}</span>
            <span style={{color:ok?"var(--accent)":"var(--danger)",fontWeight:700,fontFamily:"var(--font-mono)"}}>{ok?"✓ OK":"✗ Not ready"}</span>
          </div>
        ))}

        {/* DB mode section */}
        <div className="status-row" style={{marginTop:6,paddingTop:10,borderTop:"1px solid var(--border)"}}>
          <span style={{color:"var(--muted)"}}>Storage mode</span>
          <span className={`badge ${isSupabase?"badge-supa":"badge-sqlite"}`}>
            {isSupabase?"💾 Local + ☁ Sync":"💾 Local only"}
          </span>
        </div>
        <div className="status-row">
          <span style={{color:"var(--muted)"}}>Extraction mode</span>
          <span className="badge badge-sqlite">{extractionMode}</span>
        </div>
        <div className="status-row">
          <span style={{color:"var(--muted)"}}>Extraction strategy</span>
          <span style={{color:"var(--accent2)",fontWeight:700,fontFamily:"var(--font-mono)"}}>{extractionStrategy}</span>
        </div>
        <div className="status-row">
          <span style={{color:"var(--muted)"}}>Pending sync jobs</span>
          <span style={{color:"var(--warn)",fontWeight:700,fontFamily:"var(--font-mono)"}}>
            {health?.sync?.pending ?? 0}
          </span>
        </div>
        <div className="status-row">
          <span style={{color:"var(--muted)"}}>Failed sync jobs</span>
          <span style={{color:"var(--danger)",fontWeight:700,fontFamily:"var(--font-mono)"}}>
            {health?.sync?.failed ?? 0}
          </span>
        </div>
        <div className="status-row">
          <span style={{color:"var(--muted)"}}>Dead-letter jobs</span>
          <span style={{color:"var(--danger)",fontWeight:700,fontFamily:"var(--font-mono)"}}>
            {health?.sync?.dead_letter ?? 0}
          </span>
        </div>
        <div className="status-row">
          <span style={{color:"var(--muted)"}}>Next retry</span>
          <span style={{color:"var(--accent2)",fontWeight:700,fontFamily:"var(--font-mono)"}}>
            {health?.sync?.next_attempt_at ? formatDateTime(health.sync.next_attempt_at) : "—"}
          </span>
        </div>
        <div className="status-row">
          <span style={{color:"var(--muted)"}}>Background sync</span>
          <span style={{color:health?.background_sync?.enabled?"var(--accent)":"var(--muted)",fontWeight:700,fontFamily:"var(--font-mono)"}}>
            {health?.background_sync?.enabled ? `on / ${health?.background_sync?.interval_seconds}s` : "off"}
          </span>
        </div>
        {isSupabase&&(
          <div className="status-row">
            <span style={{color:"var(--muted)"}}>Supabase connection</span>
            <span style={{color:health?.supabase_connected?"var(--supa)":"var(--danger)",fontWeight:700,fontFamily:"var(--font-mono)"}}>
              {health?.supabase_connected?"✓ Connected":"✗ Failed"}
            </span>
          </div>
        )}
        <div className="status-row">
          <span style={{color:"var(--muted)"}}>Synced local records</span>
          <span style={{color:"var(--accent2)",fontWeight:700,fontFamily:"var(--font-mono)"}}>
            {health?.sync?.synced_records ?? 0}/{health?.sync?.total_records ?? 0}
          </span>
        </div>
        {health?.available_models?.length>0&&(
          <div style={{marginTop:10,fontSize:11,fontFamily:"var(--font-mono)",color:"var(--muted)"}}>
            Models: {health.available_models.join(", ")}
          </div>
        )}
      </div>

      {/* Supabase setup */}
      <div className="section-title" style={{marginBottom:12}}>💾 Local-First Storage</div>
      <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14,marginBottom:24}}>
        <div style={{fontWeight:700,fontSize:14,marginBottom:6}}>Every scan lands in SQLite first</div>
        <div style={{fontSize:12,color:"var(--muted)"}}>
          In hybrid mode, the app can use cloud extraction while still saving every reviewed record locally first. If cloud sync is down, unsynced jobs stay queued on this machine.
        </div>
      </div>

      <div className="section-title" style={{marginBottom:12}}>🔁 Sync Queue</div>
      <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14,marginBottom:24}}>
        {syncStatus?.queue?.length ? (
          <div style={{display:"flex",flexDirection:"column",gap:10}}>
            {syncStatus.queue.slice(0,8).map(item=>(
              <div key={item.id} style={{borderBottom:"1px solid var(--border)",paddingBottom:10}}>
                <div style={{display:"flex",justifyContent:"space-between",gap:10,marginBottom:4}}>
                  <div style={{fontSize:12,fontWeight:700}}>
                    #{item.id} · {item.operation} · {item.status}
                  </div>
                  <div style={{fontSize:11,color:"var(--muted)",fontFamily:"var(--font-mono)"}}>
                    attempts {item.attempt_count}/{health?.sync?.max_attempts ?? "?"}
                  </div>
                </div>
                <div style={{fontSize:11,color:"var(--muted)",fontFamily:"var(--font-mono)"}}>
                  next: {item.next_attempt_at ? formatDateTime(item.next_attempt_at) : "—"}
                </div>
                {item.last_error&&<div style={{fontSize:11,color:"var(--danger)",marginTop:4}}>{item.last_error}</div>}
              </div>
            ))}
          </div>
        ) : (
          <div style={{fontSize:12,color:"var(--muted)"}}>No sync queue items yet.</div>
        )}
      </div>

      <div className="section-title" style={{marginBottom:12}}>📦 Export, Import, Backup</div>
      <div style={{display:"flex",flexDirection:"column",gap:10,marginBottom:24}}>
        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:8}}>Export current local data</div>
          <div style={{fontSize:12,color:"var(--muted)",marginBottom:12}}>
            JSON includes records plus audit history. CSV is a simpler flat export for spreadsheets.
          </div>
          <div style={{display:"flex",gap:10}}>
            <button className="btn btn-ghost" style={{flex:1}} onClick={()=>downloadExport("json")} disabled={portabilityBusy!==""}>
              {portabilityBusy==="export-json"?"Exporting...":"Export JSON"}
            </button>
            <button className="btn btn-ghost" style={{flex:1}} onClick={()=>downloadExport("csv")} disabled={portabilityBusy!==""}>
              {portabilityBusy==="export-csv"?"Exporting...":"Export CSV"}
            </button>
          </div>
        </div>

        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:8}}>Create local SQLite backup</div>
          <div style={{fontSize:12,color:"var(--muted)",marginBottom:12}}>
            This copies the live SQLite database into `apps/local-backend/backups/`.
          </div>
          <div style={{display:"flex",gap:10}}>
            <button className="btn btn-primary" style={{flex:1}} onClick={createBackup} disabled={portabilityBusy!==""}>
              {portabilityBusy==="backup"?"Creating...":"DB Backup"}
            </button>
            <button className="btn btn-ghost" style={{flex:1}} onClick={createBundleBackup} disabled={portabilityBusy!==""}>
              {portabilityBusy==="bundle"?"Bundling...":"Bundle DB + Scans"}
            </button>
          </div>
        </div>

        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:8}}>Restore bundle backup</div>
          <div style={{fontSize:12,color:"var(--muted)",marginBottom:12}}>
            Use a bundle backup ZIP to restore the SQLite database and captured scans together. A fresh safety backup is created automatically first.
          </div>
          <button className="btn btn-danger btn-full" onClick={()=>restoreFileRef.current?.click()} disabled={portabilityBusy!==""}>
            {portabilityBusy==="restore"?"Restoring...":"Restore Bundle ZIP"}
          </button>
          <input ref={restoreFileRef} type="file" accept=".zip,application/zip" style={{display:"none"}} onChange={handleRestoreFile}/>
        </div>

        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:8}}>Import JSON records</div>
          <div style={{fontSize:12,color:"var(--muted)",marginBottom:12}}>
            Paste a previous JSON export or load a file. Possible duplicates are skipped automatically.
          </div>
          <div style={{display:"flex",gap:10,marginBottom:10}}>
            <button className="btn btn-ghost" style={{flex:1}} onClick={()=>importFileRef.current?.click()} disabled={portabilityBusy!==""}>Load File</button>
            <input ref={importFileRef} type="file" accept="application/json,.json" style={{display:"none"}} onChange={handleImportFile}/>
            <button className="btn btn-primary" style={{flex:1}} onClick={importJson} disabled={portabilityBusy!=="" || !importText.trim()}>
              {portabilityBusy==="import"?"Importing...":"Run Import"}
            </button>
          </div>
          <textarea
            className="search"
            rows={8}
            value={importText}
            onChange={e=>setImportText(e.target.value)}
            placeholder='{"records":[{"brand_name":"Panadol","generic_name":"Paracetamol"}]}'
            style={{resize:"vertical",minHeight:160,fontFamily:"var(--font-mono)"}}
          />
        </div>
      </div>

      <div className="section-title" style={{marginBottom:12}}>☁ Hybrid Extraction</div>
      <div style={{display:"flex",flexDirection:"column",gap:10,marginBottom:24}}>
        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:4}}>Recommended runtime mode</div>
          <div style={{fontSize:12,color:"var(--muted)",marginBottom:8}}>
            `APP_MODE=hybrid` lets the backend prefer the cloud extractor service when configured, then fall back to local LLaVA if the cloud provider is unavailable.
          </div>
          <div className="code-block">{`APP_MODE=hybrid\nCLOUD_EXTRACT_URL=https://your-cloud-api.example.com/extract\nCLOUD_EXTRACT_API_KEY=secret`}</div>
        </div>
      </div>

      <div className="section-title" style={{marginBottom:12}}>☁ Optional Supabase Sync</div>
      <div style={{display:"flex",flexDirection:"column",gap:10,marginBottom:24}}>

        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:4}}>1 · Create a Supabase project</div>
          <div style={{fontSize:12,color:"var(--muted)",marginBottom:8}}>Only needed if you want cloud replication</div>
          <div className="code-block">https://supabase.com/dashboard/new</div>
        </div>

        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:4}}>2 · Create the medicines table</div>
          <div style={{fontSize:12,color:"var(--muted)",marginBottom:8}}>Run in Supabase → SQL Editor → New query</div>
          <div className="code-block">{SUPABASE_SQL}</div>
        </div>

        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:4}}>3 · Copy credentials → apps/local-backend/.env</div>
          <div style={{fontSize:12,color:"var(--muted)",marginBottom:8}}>
            Project Settings → API → copy <strong>Project URL</strong> and <strong>service_role</strong> key
          </div>
          <div className="code-block">{ENV_EXAMPLE}</div>
        </div>

        <div style={{background:"var(--card)",border:"1px solid var(--border)",borderRadius:"var(--radius)",padding:14}}>
          <div style={{fontWeight:700,fontSize:14,marginBottom:4}}>4 · Restart the backend</div>
          <div className="code-block">{"uvicorn main:app --host 0.0.0.0 --port 8000 --reload"}</div>
        </div>
      </div>

      {/* Ollama setup */}
      <div className="section-title" style={{marginBottom:12}}>🦙 Ollama Setup</div>
      <div style={{display:"flex",flexDirection:"column",gap:10,marginBottom:24}}>
        {[
          {label:"Install Ollama", cmd:"curl -fsSL https://ollama.com/install.sh | sh", done:health?.ollama},
          {label:"Start server",   cmd:"ollama serve",                                   done:health?.ollama},
          {label:"Pull LLaVA (~4 GB)", cmd:"ollama pull llava",                          done:hasModel},
        ].map((step,i)=>(
          <div key={i} style={{background:"var(--card)",border:`1px solid ${step.done?"rgba(0,229,160,.2)":"var(--border)"}`,borderRadius:"var(--radius)",padding:14}}>
            <div style={{display:"flex",alignItems:"center",gap:10,marginBottom:8}}>
              <span style={{width:22,height:22,borderRadius:"50%",background:step.done?"var(--accent)":"var(--border)",color:step.done?"#0b0e14":"var(--muted)",display:"flex",alignItems:"center",justifyContent:"center",fontSize:11,fontWeight:800,flexShrink:0}}>
                {step.done?"✓":i+1}
              </span>
              <span style={{fontWeight:700,fontSize:14}}>{step.label}</span>
            </div>
            <div className="code-block">{step.cmd}</div>
          </div>
        ))}
      </div>

      <div style={{display:"flex",gap:10}}>
        <button className="btn btn-ghost" style={{flex:1}} onClick={check} disabled={loading}>
          {loading?<><div className="spinner"/>Checking…</>:"↺ Recheck Status"}
        </button>
        <button className="btn btn-primary" style={{flex:1}} onClick={runSync} disabled={syncing||!isSupabase}>
          {syncing?<><div className="spinner"/>Syncing…</>:"☁ Run Sync"}
        </button>
      </div>
      {portabilityToast&&<Toast msg={portabilityToast.msg} type={portabilityToast.type}/>}
    </div>
  );
}

// ── App ───────────────────────────────────────────────────────────────────────
export default function App(){
  const [tab,setTab]=useState("scan");
  const [dbKey,setDbKey]=useState(0);

  return(
    <>
      <style>{css}</style>
      <div style={{background:"var(--surface)",borderBottom:"1px solid var(--border)",padding:"14px 20px",display:"flex",alignItems:"center",gap:12}}>
        <div style={{width:32,height:32,background:"var(--accent)",borderRadius:8,display:"flex",alignItems:"center",justifyContent:"center",fontSize:18}}>💊</div>
        <div>
          <div style={{fontWeight:800,fontSize:16,letterSpacing:"-.01em"}}>MediScan</div>
          <div style={{fontSize:11,color:"var(--muted)",fontFamily:"var(--font-mono)"}}>local AI · local-first storage</div>
        </div>
      </div>
      <div className="tabs">
        {[["scan","📷 Scan"],["db","🗄 Records"],["status","⚙ Status"]].map(([id,label])=>(
          <button key={id} className={`tab ${tab===id?"active":""}`} onClick={()=>setTab(id)}>{label}</button>
        ))}
      </div>
      {tab==="scan"&&<ScannerTab onScanned={()=>setDbKey(k=>k+1)}/>}
      {tab==="db"&&<DatabaseTab key={dbKey}/>}
      {tab==="status"&&<StatusTab/>}
    </>
  );
}
