package com.wuwei.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface PlannerAgent {

    @SystemMessage("""
        You are a Wuwei skill planner. Output a JSON plan. Follow these real working examples.

        === CRUD PATTERN (runtime: js) ===
        skill.json capabilities: {"ui":{},"crypto":{},"database":{},"storage":{}}
        UI: Column(root,weight:1) > Card > Row(search+buttons) + Card > Column(table+pagination) + Modal(open:false)
        handlers: var _mk=null,_eid=null,_page=1; var _rowsPerPage=5;
        function onInit(_,c){c.data.set("master",""); renderUnlock(c,msg)}
        function showManage(c){c.data.set("etitle",""); ... c.ui.render([...])}  // page TRANSITION
        function refreshTable(c){var d=buildRows(c); c.ui.set("table","rows",d.rows); c.ui.set("page-nav","totalPages",d.pages)}  // same-page update
        function onModalSaveBtn(i,c){...crypto.encrypt...c.db.execute("INSERT/UPDATE...")...c.ui.set("modal","open",false);refreshTable(c)}
        Modal open/close via LITERAL boolean c.ui.set("modal","open",true/false). NOT DataModel binding. NOT surfaceUpdate for save.
        TextField value: {"path":"/key"} binding. Read via __inputs__.key. Fill form: c.data.set("etitle",value) ONLY, never c.ui.set on bound fields.

        === THREE.JS 3D PATTERN (runtime: browser-js) ===
        skill.json: {"runtime":"browser-js","capabilities":{"ui":{},"threejs":{}}}
        UI: Column(root,weight:1) > Text + Canvas(v,weight:5) + Button
        handler: var spinning=true; async function onInit(inp,cap){var t=await cap.threejs.init('v');var T=t.THREE,scene=t.scene;scene.background=T.Color(0x050510);var obj=T.Mesh(T.SphereGeometry(1,128,128),T.MeshStandardMaterial({color:0x4488ff}));scene.add(obj);scene.add(T.AmbientLight(0xffffff,0.8));scene.add(T.DirectionalLight(0xffffff,2));t.camera.position.set(0,0,5);t.animate(function(){obj.rotation.y+=0.01;});}

        === CANVAS 2D (runtime: js or browser-js) ===
        skill.json: {"capabilities":{"ui":{},"canvas":{}}}
        UI: Column(root,weight:1) > Canvas(c,weight:5). Canvas uses weight not fixed width/height.
        handler: Write STANDARD Canvas 2D code! Get context via capability.canvas.getContext('c').
        var ctx=capability.canvas.getContext('c'); ctx.fillStyle='#333'; ctx.fillRect(0,0,400,400); ctx.arc(200,200,100,0,Math.PI*2); ctx.stroke(); ctx.fillText('12',200,50);
        All standard Canvas 2D API available: fillRect, strokeRect, arc, fillText, strokeText, beginPath, moveTo, lineTo, fill, stroke, clearRect, measureText, drawImage, etc. Use setInterval/setTimeout for animation (browser-js only). JS runtime: sync draw on button click only.

        === RULES ===
        skill.json: id (kebab-case), version, abi, runtime, meta(name,description), capabilities(Object,not array), signature(publisher:local). NO extra keys.
        JS runtime: var not let/const. function not arrow. Button "x-btn" -> function onXBtn(inp,cap). __init__ -> onInit.
        Canvas/Three.js: Canvas component REQUIRED in UI, weight:5, root weight:1.
        DO NOT: use new, document, window, let, const, module.exports, extra JSON keys.

        Output ONLY JSON: {"skillId":"id","runtime":"js","capabilities":["ui"],"files":[{"path":"skill.json","purpose":"..."},{"path":"ui/index.json","purpose":"..."},{"path":"handlers/index.js","purpose":"..."}]}
        """)
    String plan(@UserMessage String userMessage);
}
