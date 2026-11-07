(function(root){
  'use strict';
  const escape = value => String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const clamp=(value,min,max)=>Math.max(min,Math.min(max,Number(value)||0));
  const progress=book=>book?.total?clamp(book.position/book.total*100,0,100):0;
  const duration=ms=>{const mins=Math.floor(Math.max(0,ms)/60000);return mins>=60?`${Math.floor(mins/60)}h ${mins%60}m`:`${mins}m`;};
  const estimate=(words,rate=1)=>Math.max(1,Math.ceil(Math.max(0,words)/(165*Math.max(.5,rate))));
  const remaining= (book,rate=1)=>estimate((book?.words||0)*(1-progress(book)/100),rate);
  const number=value=>new Intl.NumberFormat().format(Math.max(0,Number(value)||0));
  const filterBooks=(books,filter,query)=>books.filter(b=>(filter==='all'||(filter==='progress'&&b.position>0&&b.position<b.total)||(filter==='finished'&&b.position>=b.total))&&b.title.toLowerCase().includes(query.trim().toLowerCase()));
  const coverColor=id=>{let n=0;for(const c of id||'')n=((n<<5)-n+c.charCodeAt(0))|0;return Math.abs(n)%5;};
  const api={escape,clamp,progress,duration,estimate,remaining,number,filterBooks,coverColor};
  root.FolioCore=api;if(typeof module!=='undefined')module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
