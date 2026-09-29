import os, asyncio, httpx, sys, json
from dotenv import load_dotenv; load_dotenv('/app/backend/.env')
tok = os.environ.get('TMDB_BEARER_TOKEN')
cols = {'mib':86055,'ghostbusters':2980,'austin':1006,'lethal':945,'rambo':5039,'predator':399,'blade':735,'underworld':2326,'resident-evil':17255,'final-destination':8864,'paranormal':41437,'insidious':228446,'saw':656,'halloween':91361,'friday13':9735,'elm':8581,'chucky':10455,'evil-dead':1960,'hellraiser':8917,'purge':256322,'hangover':86119,'american-pie':2806,'meet-parents':51509,'rush-hour':90863,'bad-boys':14890,'bhcop':85861,'home-alone':9888,'museum':85943,'madagascar':14740,'cars':87118,'frozen':386382,'incredibles':468222,'nemo':137697,'monsters':137696,'ralph':404825,'hotel-t':185103,'pets':427084,'lego':325470,'sonic':720879,'jumanji':495527,'avatar':87096,'dune':726871,'blade-runner':422837,'riddick':2794,'divergent':283579,'maze-runner':295130,'percy':179532,'narnia':420,'fifty':344830,'kingsman':391860,'sherlock':102322,'expendables':126125,'kill-bill':2883,'godfather':230,'karate-kid':8580,'ace':3167,'scary-movie':4246,'mummy':1733,'davinci':115776,'reacher':403374,'fantastic4':9744,'wolverine':453993,'venom':558216,'antman':422834,'dr-strange':618529,'iron-man':131292,'thor':131296,'cap':131295,'gotg':284433,'wonder-woman':468552,'superman':8537,'tron':34433,'pacific-rim':363369,'national-treasure':52984,'now-you-see-me':314151,'equalizer':526683,'taken':86860,'pitch-perfect':229932}
comps = {'sony':34,'columbia':5,'mgm':21,'newline':12,'lucas':1,'amblin':56,'focus':10146,'searchlight':43,'miramax':14,'aardman':297,'laika':11537,'wdas':6125,'spa':2251,'badrobot':11461,'workingtitle':10163,'village':79,'screengems':7405,'touchstone':9195,'dccomics':429,'hbo':3268,'appletv':194232,'netflix':178464,'amazon':20580,'marvel-tv':7505,'lionsgate':1632,'studiocanal':694,'gaumont':9,'toho':882,'bbcfilms':288,'film4':11308,'annapurna':84439,'neon':90733,'plan-b':81,'skydance':82819,'tsg':22213,'dune-ent':444,'happy-madison':2608,'apatow':10105,'jerry-b':130,'di-bonaventura':435,'original-film':333,'one-race':10761,'syncopy':9996,'legendary':923,'atlas':10405,'ghost-house':768,'twisted':2340,'platinum-dunes':1171,'monkeypaw':124258,'blumhouse':3172,'a24':41077}
async def main():
    async with httpx.AsyncClient(timeout=20, headers={'Authorization': f'Bearer {tok}'}) as c:
        async def g(p):
            r = await c.get('https://api.themoviedb.org/3' + p)
            return r.json() if r.status_code == 200 else {'err': r.status_code}
        cr = await asyncio.gather(*[g(f'/collection/{i}') for i in cols.values()])
        for k, d in zip(cols, cr):
            print('COL', k, cols[k], d.get('name') or d.get('err'), len(d.get('parts') or []))
        pr = await asyncio.gather(*[g(f'/company/{i}') for i in comps.values()])
        for k, d in zip(comps, pr):
            print('CO', k, comps[k], d.get('name') or d.get('err'), bool(d.get('logo_path')))
asyncio.run(main())
