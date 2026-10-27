/* Developer example only. This reserved hostname is not a source or preset website. */
ClearStreamTVPlugins.register({
  id:'demo.player-adapter',
  hosts:['demo.example'],
  mount:function(ctx){
    ctx.addStyle('.demo-film-card { min-height: 64px; font-size: 22px; }');
    ctx.observe(function(){
      ctx.focusable('.demo-episode');
      ctx.hide('.demo-ad-banner');
      ctx.clickOnce('button[data-demo-skip-ad]');
    });
  }
});
