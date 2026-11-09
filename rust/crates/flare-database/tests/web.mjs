// Requires Playwright. FLARE_PLAYWRIGHT_MODULE and FLARE_CHROMIUM can select installed tools.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, sep, isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';

const moduleName = process.env.FLARE_PLAYWRIGHT_MODULE || 'playwright';
const { chromium } = await import(isAbsolute(moduleName) ? pathToFileURL(moduleName).href : moduleName);
const root = resolve(process.argv[2] || 'target/database-web');
const html = `<!doctype html><title>Flare database test</title><script type="module">
import init, * as client from '/flare_database_client.js';
await init(); globalThis.client=client; globalThis.ready=true;
</script>`;
const server = createServer(async (request, response) => {
    try {
        const path = new URL(request.url, 'http://localhost').pathname;
        if (path === '/') { response.setHeader('Content-Type', 'text/html'); response.end(html); return; }
        if (path === '/favicon.ico') { response.writeHead(204).end(); return; }
        const file = resolve(root, `.${path}`);
        if (!file.startsWith(root + sep)) { response.writeHead(403).end(); return; }
        response.setHeader('Content-Type', file.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        response.end(await readFile(file));
    } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true, executablePath: process.env.FLARE_CHROMIUM });
const context = await browser.newContext();
const name = `test_${crypto.randomUUID().replaceAll('-', '')}`;
const errors = [];
async function page() {
    const page = await context.newPage();
    page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
    page.on('console', message => { if (message.type() === 'error') console.error(message.text()); });
    await page.goto(origin);
    await page.waitForFunction(() => globalThis.ready);
    await page.evaluate(name => client.open(name, '/worker.js'), name);
    return page;
}
const query = (page, value) => page.evaluate(([name, value]) => client.query(name, JSON.stringify(value)), [name, value]);
const write = (page, value) => page.evaluate(([name, value]) => client.write(name, typeof value === 'string' ? value : JSON.stringify(value)), [name, value]);
const next = page => page.evaluate(() => client.next('accounts'));
const row = key => ({ account_key: key, credential_json: '{"fake":"only","future":null}', platform_type: 'plugin', last_active: 1, sort_id: 0 });
const agentName = `${name}_agent`;
const agentQuery = page => page.evaluate(name => client.agent_query(name, '{"History":"c"}'), agentName);
const agentWrite = (page, value) => page.evaluate(([name, value]) => client.agent_write(name, typeof value === 'string' ? value : JSON.stringify(value)), [agentName, value]);
const agentNext = page => page.evaluate(() => client.agent_next('agent-history'));
const cacheName = `${name}_cache`;
const cacheQuery = (page, value) => page.evaluate(([name,value])=>client.cache_query(name,JSON.stringify(value)),[cacheName,value]);
const cacheWrite = (page, epoch, value) => page.evaluate(([name,epoch,value])=>client.cache_write(name,epoch,typeof value==='string'?value:JSON.stringify(value)),[cacheName,epoch,value]);
const cacheNext = page=>page.evaluate(()=>client.cache_next('cache-home'));
const cacheIdentity = {TimelineIdentities:{paging_key:'home',range:{offset:0,limit:1000,descending:false}}};
const cacheStatus=(id,payload='{}')=>({id:`1:a${id.length}:${id}`,account:'a',status_key:id,payload,fingerprint:0,render_hash:0,text:'cache %_\\',version:''});
const cachePage = (metadata)=>({paging_key:'home',expected:{generation:metadata.generation,revision:metadata.revision},statuses:[],references:[],items:[],presentations:[],remove_ids:[],next_key:'opaque',keep_next_key:false,prev_key:null,source_heads:'{}',account_keys:['a']});

async function checkCache(first,second) {
    for(const page of [first,second])await page.evaluate(name=>client.cache_open(name,'/worker.js'),cacheName);
    await assert.rejects(first.evaluate(name=>client.cache_open(name,'/worker.js'),name));
    await assert.rejects(first.evaluate(name=>client.agent_open(name,'/worker.js'),cacheName));
    const epoch=JSON.parse(await cacheQuery(first,'Info')).Info.epoch;
    const metadata=JSON.parse(await cacheWrite(first,epoch,[{ResetTimeline:{paging_key:'home',account_keys:['a']}}])).pages[0];
    await second.evaluate(([name,q])=>client.cache_observe(name,'cache-home',JSON.stringify(q)),[cacheName,cacheIdentity]);
    assert.equal(JSON.parse(await cacheNext(second)).Identities.rows.length,0);
    const payload=cachePage(metadata);
    payload.statuses=Array.from({length:600},(_,i)=>cacheStatus(String(i)));
    payload.items=payload.statuses.map((s,i)=>({paging_key:'home',timeline_id:s.status_key,status_id:s.id,sort_id:i,is_gap:false,message:null,message_render_hash:i===0?1:null,notification_key:null,content_revision:0}));
    const raw=JSON.stringify([{CommitPage:payload}]).replace('"message_render_hash":1','"message_render_hash":9007199254740993');
    const timing=await first.evaluate(async([name,epoch,payload])=>{
        let ticks=0;const timer=setInterval(()=>ticks++,2);
        try {const receipt=await client.cache_write(name,epoch,payload);return {ticks,receipt};}finally{clearInterval(timer);}
    },[cacheName,epoch,raw]);
    assert.ok(timing.ticks>0,'cache SQL blocked the page');
    const changed=await cacheNext(second);assert.match(changed,/9007199254740993/);
    assert.equal(JSON.parse(changed).Identities.rows.length,600);
    const current=JSON.parse(timing.receipt).pages[0];
    const claims=await Promise.allSettled([first,second].map(p=>cacheWrite(p,epoch,[{CommitPage:cachePage(current)}])));
    assert.equal(claims.filter(v=>v.status==='fulfilled').length,1,'a page guard may be consumed only once');
    await cacheNext(second);
    await checkCacheContracts(first,second,epoch);
    await assert.rejects(cacheWrite(first,'stale-epoch',[{ResetCache:null}]));
    return epoch;
}

async function checkCacheContracts(first,second,epoch){
    const range={offset:550,limit:100,descending:false};
    const statuses=JSON.parse(await cacheQuery(first,{SearchStatuses:{text:'%_\\',range}})).StatusPage;
    assert.equal(statuses.root_ids.length,50);assert.equal(statuses.bundle.statuses.length,50);assert.equal(statuses.has_more,false);
    const users=Array.from({length:150},(_,i)=>({user_key:`u${String(i).padStart(3,'0')}`,name:'same %_\\',canonical_handle:'',host:'',payload:'{}'}));
    await cacheWrite(first,epoch,users.flatMap(u=>[{Put:{User:u}},{Put:{UserHistory:{account:'a',user_key:u.user_key,last_visit:1}}}]));
    for(const query of [{SearchUsers:{text:'%_\\',range:{...range,offset:100}}},{UserHistory:{account:'a',range:{...range,offset:100}}}]){
        const result=JSON.parse(await cacheQuery(second,query)).RecordsPage;assert.equal(result.records.length,50);assert.equal(result.has_more,false);
    }
    const original=JSON.parse(await cacheQuery(first,{Status:'1:a1:0'})).Status;assert.equal(original.version.length,32);
    const results=await Promise.allSettled([first,second].map((p,i)=>cacheWrite(p,epoch,[{UpdateStatus:{expected_version:original.version,status:{...original,payload:JSON.stringify({winner:i})}}}])));
    assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
    await cacheNext(second);
    await Promise.all([first,second].map((p,i)=>p.evaluate(([name,epoch,field])=>client.cache_mark(name,epoch,'1:a1:0',field),[cacheName,epoch,i===0?'likes':'bookmarks'])));
    const updated=JSON.parse(JSON.parse(await cacheQuery(first,{Status:'1:a1:0'})).Status.payload);assert.equal(updated.likes,true);assert.equal(updated.bookmarks,true);
    const snapshot=JSON.parse(await cacheNext(second)).Identities;
    if(snapshot.rows[0].content_revision<JSON.parse(await cacheQuery(second,cacheIdentity)).Identities.rows[0].content_revision)await cacheNext(second);
    const pending={id:'6:Status1:t2:zh',entity_type:'Status',entity_key:'t',target_language:'zh',source_hash:'h',status:'Pending',display_mode:'Auto',payload:'{"previous":true}',status_reason:'previous',attempt_count:1,updated_at:1};
    await cacheWrite(first,epoch,[{Put:{Translation:pending}}]);
    const start={StartTranslation:{id:pending.id,source_hash:'h',attempt_count:1,updated_at:2}};
    const claims=await Promise.allSettled([first,second].map(p=>cacheWrite(p,epoch,[start])));assert.equal(claims.filter(r=>r.status==='fulfilled').length,1);
    await cacheWrite(first,epoch,[{FailStaleTranslations:{before:3,reason:'interrupted',updated_at:3}}]);
    const translations=JSON.parse(await cacheQuery(second,{Translations:{entity_type:'Status',entity_keys:['t'],language:'zh'}})).Records;
    assert.equal(translations[0].Translation.status,'Failed');assert.equal(translations[0].Translation.payload,null);
    await checkCacheStorageRegressions(first,second,epoch);
}

async function checkCacheStorageRegressions(first,second,epoch){
    await second.evaluate(name=>client.cache_subscription_released(name),cacheName);
    const range={offset:0,limit:1,descending:false};
    const root={...cacheStatus('snapshot-root'),text:'graph-probe'};
    const quoted={...cacheStatus('snapshot-quote'),text:null};
    const translation={id:`6:Status${root.id.length}:${root.id}2:zh`,entity_type:'Status',entity_key:root.id,target_language:'zh',source_hash:'h',status:'Pending',display_mode:'Auto',payload:null,status_reason:null,attempt_count:1,updated_at:1};
    const metadata=JSON.parse(await cacheWrite(first,epoch,[{ResetTimeline:{paging_key:'snapshots',account_keys:['a']}}])).pages[0];
    await cacheWrite(first,epoch,[{CommitPage:{...cachePage(metadata),paging_key:'snapshots',statuses:[root,quoted],references:[{status_id:root.id,references:[{status_id:root.id,reference_type:'Quote',reference_status_id:quoted.id,reference_order:0}]}],items:[{paging_key:'snapshots',timeline_id:'root',status_id:root.id,sort_id:1,is_gap:false,message:null,message_render_hash:null,notification_key:null,content_revision:0}]}},{Put:{Translation:translation}}]);
    const full={TimelinePage:{paging_key:'snapshots',range}};
    const identities={TimelineIdentities:{paging_key:'snapshots',range}};
    for(const [id,q] of [['cache-full',full],['cache-render',identities]]){
        await second.evaluate(([name,id,q])=>client.cache_observe(name,id,JSON.stringify(q)),[cacheName,id,q]);
        await second.evaluate(id=>client.cache_next(id),id);
    }
    await second.evaluate(()=>{globalThis.cacheRenderChanged=false;globalThis.cacheRenderPending=client.cache_next('cache-render').then(value=>{cacheRenderChanged=true;return value;});});
    await cacheWrite(first,epoch,[{TranslationDisplay:{id:translation.id,display_mode:'Auto',updated_at:2}}]);
    assert.equal(await second.evaluate(()=>client.cache_next('cache-full')),await cacheQuery(first,full));
    const current=JSON.parse(await cacheQuery(first,{Status:root.id})).Status;
    await cacheWrite(first,epoch,[{UpdateStatus:{expected_version:current.version,status:{...current,text:'graph-probe edited'}}}]);
    assert.equal(await second.evaluate(()=>client.cache_next('cache-full')),await cacheQuery(first,full));
    await second.waitForTimeout(120);assert.equal(await second.evaluate(()=>cacheRenderChanged),false);
    await cacheWrite(first,epoch,[{Put:{Status:{...root,payload:'{"render":true}'}}}]);
    assert.equal(await second.evaluate(()=>cacheRenderPending),await cacheQuery(first,identities));
    const search=JSON.parse(await cacheQuery(second,{SearchStatuses:{text:'graph-probe',range}})).StatusPage;
    assert.deepEqual(search.root_ids,[root.id]);assert.equal(search.bundle.statuses.length,2);
    assert.equal(search.bundle.references[0].reference_status_id,quoted.id);assert.equal(search.bundle.translations[0].updated_at,2);

    const dm=(message_key,timestamp,is_local=false)=>({message_key,room_key:'room',user_key:'sender',timestamp,payload:'{}',show_sender:false,is_local,remote_cursor:null,revision:0});
    const room=async()=>JSON.parse(await cacheQuery(second,{Room:{account:'a',room_key:'room'}})).Joined.records;
    const checkRoom=async(sort,last)=>{const rows=await room();assert.equal(rows.find(r=>r.RoomTimeline).RoomTimeline.sort_id,sort);assert.equal(rows.find(r=>r.Room).Room.last_message_key,last);};
    await cacheWrite(first,epoch,[{Put:{RoomTimeline:{account:'a',room_key:'room',sort_id:0,unread_count:2,payload:'{}'}}},{Put:{Room:{room_key:'room',platform_id:'test',last_message_key:null}}},...[dm('old',100),dm('new',200),dm('local',300,true)].map(Message=>({Put:{Message}}))]);
    await checkRoom(200,'new');await cacheWrite(first,epoch,[{Delete:{Message:'new'}}]);await checkRoom(100,'old');
    await cacheWrite(first,epoch,[{AcknowledgeMessage:{local_key:'local',expected_revision:0,remote:dm('ack',150)}}]);await checkRoom(150,'ack');
    await cacheWrite(first,epoch,[{Put:{Message:dm('ack',80)}}]);await checkRoom(100,'old');
    await assert.rejects(cacheWrite(first,epoch,[{ReplaceRoomMessages:{room_key:'room',messages:[dm('first',1),{...dm('invalid',2),payload:'{'}]}}]));
    assert.equal(JSON.parse(await cacheQuery(second,{Message:'first'})).Message,null);await checkRoom(100,'old');
    await cacheWrite(first,epoch,[{ReplaceRoomMessages:{room_key:'room',messages:[dm('fresh',3)]}}]);
    assert.equal(JSON.parse(await cacheQuery(second,{Message:'ack'})).Message,null);assert.equal(JSON.parse(await cacheQuery(second,{Message:'fresh'})).Message.timestamp,3);
    await cacheWrite(first,epoch,[{ReplaceRoomMessages:{room_key:'room',messages:[]}}]);await checkRoom(0,null);

    await cacheWrite(first,epoch,['missing','visible','missing-again','visible-again'].map(member_key=>({Put:{ListMember:{list_key:'members',member_key}}})).concat(['visible','visible-again'].map(user_key=>({Put:{User:{user_key,name:'',canonical_handle:'',host:'',payload:'{}'}}}))));
    const members=JSON.parse(await cacheQuery(second,{ListMembers:{list_key:'members',range}})).RecordsPage;
    assert.equal(members.users[0].user_key,'visible');assert.equal(members.has_more,true);
    const keys=JSON.parse(await cacheQuery(second,{ListMemberKeys:{list_key:'members',range:{...range,limit:10}}})).RecordsPage;
    assert.equal(keys.records.length,4);assert.equal(keys.users.length,0);
    await cacheWrite(first,epoch,Array.from({length:150},(_,i)=>({Put:{EmojiHistory:{account:'a',short_code:`${i<50?'keep':'removed'}${i}`,last_use:i}}})));
    const emoji=JSON.parse(await cacheQuery(second,{EmojiHistory:{account:'a',range:{offset:100,limit:100,descending:true}}})).RecordsPage;
    assert.equal(emoji.records.length,50);assert.equal(emoji.has_more,false);assert.ok(emoji.records.every(r=>r.EmojiHistory.short_code.startsWith('keep')));
    const account='scan-account';
    const scanned={...cacheStatus('root'),id:`${account.length}:${account}4:root`,account};
    for(const paging_key of ['scan-left','scan-right']){
        const metadata=JSON.parse(await cacheWrite(first,epoch,[{ResetTimeline:{paging_key,account_keys:[account]}}])).pages[0];
        await cacheWrite(first,epoch,[{CommitPage:{...cachePage(metadata),paging_key,account_keys:[account],statuses:[scanned],items:[{paging_key,timeline_id:'same',status_id:scanned.id,sort_id:1,is_gap:false,message:null,message_render_hash:null,notification_key:null,content_revision:0}]}}]);
    }
    let after=null;const visited=[];
    do{
        const scan=JSON.parse(await cacheQuery(second,{AccountTimelines:{account,after,limit:1}})).TimelineScan;
        assert.equal(scan.info.epoch,epoch);assert.equal(scan.pages.length,1);
        const fragment=scan.pages[0],row=fragment.rows[0];visited.push(row.paging_key);
        await cacheWrite(first,epoch,[{CommitPage:{...cachePage(fragment.metadata),paging_key:row.paging_key,account_keys:[account],remove_ids:[row.timeline_id],keep_next_key:true}}]);
        after=scan.next;
    }while(after);
    assert.deepEqual(visited,['scan-left','scan-right']);
    assert.equal(JSON.parse(await cacheQuery(second,{Status:scanned.id})).Status.account,account);
    await checkPageLifecycle(first,second,epoch);
}

async function checkPageLifecycle(first,second,epoch){
    await second.evaluate(name=>client.cache_observe(name,'cache-lifecycle',JSON.stringify({Status:'1:a1:0'})),cacheName);
    await second.evaluate(()=>client.cache_next('cache-lifecycle'));
    await second.evaluate(()=>{
        globalThis.lifecycleChanged=false;
        globalThis.lifecyclePending=client.cache_next('cache-lifecycle').then(value=>{lifecycleChanged=true;return {value};},error=>{lifecycleChanged=true;return {error:String(error)};});
        dispatchEvent(new Event('pagehide'));
    });
    await cacheQuery(second,'Info');
    await cacheWrite(first,epoch,[{Put:{Status:cacheStatus('0','{"afterHide":true}')}}]);
    await second.waitForTimeout(120);assert.equal(await second.evaluate(()=>lifecycleChanged),false,'pagehide must release this client\'s owner subscriptions');
    await second.evaluate(()=>dispatchEvent(new Event('pageshow')));
    let result=await second.evaluate(()=>lifecyclePending);
    for(let i=0;result.error&&i<3;i++)result=await second.evaluate(()=>client.cache_next('cache-lifecycle').then(value=>({value}),error=>({error:String(error)})));
    assert.equal(JSON.parse(JSON.parse(result.value).Status.payload).afterHide,true);
}

async function checkAgent(first, second) {
    for (const page of [first, second]) await page.evaluate(name => client.agent_open(name, '/worker.js'), agentName);
    await assert.rejects(first.evaluate(name => client.agent_open(name, '/worker.js'), name));
    await assert.rejects(first.evaluate(name => client.open(name, '/worker.js'), agentName));
    await second.evaluate(name => client.agent_observe(name, 'agent-history', '{"History":"c"}'), agentName);
    assert.deepEqual(JSON.parse(await agentNext(second)), { History:null });
    const claims = await Promise.allSettled([first, second].map(page => agentWrite(page, [{ClaimRun:{conversation_id:'c',now:1}}])));
    assert.equal(claims.filter(value => value.status === 'fulfilled').length, 1);
    const run = JSON.parse(claims.find(value => value.status === 'fulfilled').value).runs[0].run_id;
    assert.equal(JSON.parse(await agentNext(second)).History.conversation.run_id, run);
    const mutations = [
        {InsertMessages:{conversation_id:'c',run_id:run,placement:'Append',messages:[{
            message_id:'question',role:'User',text:'fake question',content_json:'[{"future":true}]',message_json:'{"fake":true}',created_at:1,
        }]}},
        {SetContext:{conversation_id:'c',run_id:run,through_message_id:'question',summary:'fake summary'}},
    ];
    await agentWrite(first, JSON.stringify(mutations).replace('"created_at":1', '"created_at":9007199254740993'));
    const changed = await agentNext(second);
    assert.match(changed, /9007199254740993/);
    const history = JSON.parse(changed).History;
    assert.equal(history.messages.length, 1);
    assert.equal(history.context.through_position, 0);
    assert.equal(history.conversation.run_id, run);
    return run;
}

try {
    const first = await page();
    const second = await page();
    await first.evaluate(name => {
        const channel=new BroadcastChannel(`flare-rust-app:${name}`);
        channel.postMessage(JSON.stringify({protocol:999,message:{Hello:{client:'stale-client'}}}));
        channel.close();
    },name);
    const retryName=`retry_${name}`;
    await first.evaluate(async name => {
        let invalidUrl=false;
        try { await client.open(name,'http://['); } catch { invalidUrl=true; }
        if (!invalidUrl) throw new Error('invalid worker URL unexpectedly opened');
        let failed=false;
        try { await client.open(name,'/missing-worker.js'); } catch { failed=true; }
        if (!failed) throw new Error('invalid worker unexpectedly opened');
        await client.open(name,'/worker.js');
        await client.query(name,'"Accounts"');
        client.close(name);
    },retryName);
    assert.deepEqual(JSON.parse(await query(first, 'Accounts')), { Accounts: [] });
    await second.evaluate(name => client.observe(name, 'accounts', '"Accounts"'), name);
    assert.deepEqual(JSON.parse(await next(second)), { Accounts: [] });

    const changed = next(second);
    const large = '[{"UpsertAccount":{"account_key":"large","credential_json":"{\\"fake\\":true}","platform_type":"future","last_active":9007199254740993,"sort_id":0}}]';
    await write(first, large);
    assert.match(await changed, /9007199254740993/);
    assert.match(await query(second, 'Accounts'), /9007199254740993/);

    // A failed transaction and an equal result must leave the pending subscription asleep.
    await second.evaluate(() => { globalThis.observed=false; globalThis.pending=client.next('accounts').then(value => { observed=true; return value; }); });
    await assert.rejects(write(first, [
        { UpsertAccount: row('must-rollback') },
        { UpsertDraftTarget: { group_id:'missing',account_key:'a',status:'FAILED',error_message:null,attempt_count:0,last_attempt_at:null,created_at:0,updated_at:0,target_id:'invalid' } },
    ]));
    await write(first, large);
    await second.waitForTimeout(120);
    assert.equal(await second.evaluate(() => observed), false);
    assert.equal(JSON.parse(await query(second, 'Accounts')).Accounts.length, 1);
    await write(first, [{ UpsertAccount: row('next') }]);
    assert.equal(JSON.parse(await second.evaluate(() => pending)).Accounts.length, 2);

    // SQL and persistence execute outside the page's event loop.
    const many = Array.from({ length: 8000 }, (_, i) => ({ UpsertAccount: row(`bulk-${i}`) }));
    const timing = await first.evaluate(async ([name, payload]) => {
        let ticks=0;
        const timer=setInterval(() => ticks++, 2);
        const started=performance.now();
        try { await client.write(name, JSON.stringify(payload)); }
        finally { clearInterval(timer); }
        return { ticks, elapsed:performance.now()-started };
    }, [name, many]);
    assert.ok(timing.ticks > 0, `page event loop did not advance: ${JSON.stringify(timing)}`);
    const agentRun = await checkAgent(first, second);
    const cacheEpoch = await checkCache(first, second);

    // Closing the original tab releases its lock. A waiting worker takes ownership.
    await first.close();
    let recovered;
    for (let attempt=0; attempt<100; attempt++) {
        try { recovered=JSON.parse(await query(second, 'Accounts')); break; }
        catch { await second.waitForTimeout(50); }
    }
    assert.equal(recovered?.Accounts.length, 8002);
    await write(second, [{ UpsertAccount: row('after-owner-change') }]);
    let resumed = false;
    for (let attempt=0; attempt<4; attempt++) {
        try {
            const snapshot=JSON.parse(await next(second));
            if (snapshot.Accounts.length===8003) { resumed=true; break; }
        } catch (error) {
            if (attempt===3) throw error;
        }
    }
    assert.ok(resumed, 'subscription did not recover after owner takeover');

    let agentRecovered;
    for (let attempt=0; attempt<100; attempt++) {
        try { agentRecovered=JSON.parse(await agentQuery(second)).History; break; }
        catch { await second.waitForTimeout(50); }
    }
    assert.equal(agentRecovered?.conversation.run_id, agentRun);
    assert.equal(agentRecovered?.messages.length, 1);
    await agentWrite(second, [{InsertMessages:{conversation_id:'c',run_id:agentRun,placement:'Append',messages:[{
        message_id:'after-owner',role:'Assistant',text:'fake reply',content_json:'[]',message_json:'{}',created_at:2,
    }]}}]);
    let agentResumed=false;
    for (let attempt=0; attempt<4; attempt++) {
        try { if (JSON.parse(await agentNext(second)).History.messages.length===2) { agentResumed=true; break; } }
        catch (error) { if (attempt===3) throw error; }
    }
    assert.ok(agentResumed, 'Agent subscription did not recover');

    let cacheRecovered;
    for(let attempt=0;attempt<100;attempt++){
        try{cacheRecovered=JSON.parse(await cacheQuery(second,cacheIdentity)).Identities;break;}
        catch{await second.waitForTimeout(50);}
    }
    assert.equal(cacheRecovered?.rows.length,600);
    assert.equal(cacheRecovered.info.epoch,cacheEpoch);
    await cacheWrite(second,cacheEpoch,[{Put:{Status:cacheStatus('0','{"afterTakeover":true}')}}]);
    let cacheResumed=false;
    for(let attempt=0;attempt<4;attempt++){
        try{if(JSON.parse(await cacheNext(second)).Identities.rows[0].content_revision>cacheRecovered.rows[0].content_revision){cacheResumed=true;break;}}
        catch(error){if(attempt===3)throw error;}
    }
    assert.ok(cacheResumed,'Cache subscription did not recover');

    await second.close();
    const reopened=await page();
    assert.equal(JSON.parse(await query(reopened, 'Accounts')).Accounts.length, 8003);
    assert.match(await query(reopened, { Account:'large' }), /9007199254740993/);
    await reopened.evaluate(name => client.agent_open(name, '/worker.js'), agentName);
    const agentReopened = await agentQuery(reopened);
    assert.match(agentReopened, /9007199254740993/);
    assert.equal(JSON.parse(agentReopened).History.messages.length, 2);
    await reopened.evaluate(name=>client.cache_open(name,'/worker.js'),cacheName);
    const cacheReopened=await cacheQuery(reopened,cacheIdentity);assert.match(cacheReopened,/9007199254740993/);
    assert.equal(JSON.parse(cacheReopened).Identities.rows.length,600);
    const cleared=JSON.parse(await cacheWrite(reopened,cacheEpoch,['ResetCache']));assert.notEqual(cleared.epoch,cacheEpoch);
    await assert.rejects(cacheWrite(reopened,cacheEpoch,[{Put:{Status:cacheStatus('late')}}]));
    assert.equal(JSON.parse(await cacheQuery(reopened,cacheIdentity)).Identities.rows.length,0);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed:true, rows:8003, eventLoop:timing, checks:['shared owner','cross-tab subscriptions','rollback','dedup','i64','nonblocking SQL','owner takeover','OPFS reopen','Agent isolation','exclusive run claims','Agent checkpoint','Agent takeover and reopen','Cache isolation','Cache page CAS','Cache full snapshots and render identities','Cache hydrated search','Cache remote DM previews and replacement','Cache visible member paging','Cache emoji history paging','Cache takeover and reopen','Cache clear epoch'] }));
} finally {
    await context.close();
    await browser.close();
    await new Promise(resolve => server.close(resolve));
}
