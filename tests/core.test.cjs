const test=require('node:test');
const assert=require('node:assert/strict');
const C=require('../app/src/main/assets/core.js');
test('untrusted PDF metadata is escaped before HTML rendering',()=>{assert.equal(C.escape('<img src=x onerror="alert(1)">'), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;');assert.equal(C.escape("O'Reilly & friends"),'O&#39;Reilly &amp; friends');});
test('progress is bounded and handles empty data',()=>{assert.equal(C.progress(null),0);assert.equal(C.progress({total:10,position:5}),50);assert.equal(C.progress({total:10,position:20}),100);assert.equal(C.progress({total:0,position:20}),0);});
test('filters reflect current progress and are case insensitive',()=>{const b=[{title:'First',position:0,total:4},{title:'Second',position:2,total:4},{title:'Third',position:4,total:4}];assert.deepEqual(C.filterBooks(b,'progress',' SEC ').map(x=>x.title),['Second']);assert.equal(C.filterBooks(b,'finished','').length,1);assert.equal(C.filterBooks(b,'all','missing').length,0);});
test('listening estimates scale with speed',()=>{assert.equal(C.estimate(1650,1),10);assert.equal(C.estimate(1650,2),5);assert.equal(C.remaining({words:1650,total:10,position:5},1),5);});
test('duration uses whole minutes and supports hours',()=>{assert.equal(C.duration(0),'0m');assert.equal(C.duration(3660000),'1h 1m');});
test('cover palette assignment is deterministic',()=>{assert.equal(C.coverColor('book'),C.coverColor('book'));assert.ok(C.coverColor('book')>=0&&C.coverColor('book')<5);});
