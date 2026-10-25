/* Datos de ejemplo. En la app instalada se reemplazan por listings.json (ver README). */
var PORTALS={
  zonaprop:{name:'ZonaProp',code:'ZP',url:'https://www.zonaprop.com.ar/'},
  argenprop:{name:'ArgenProp',code:'AP',url:'https://www.argenprop.com/'},
  meli:{name:'MercadoLibre',code:'ML',url:'https://inmuebles.mercadolibre.com.ar/'},
  fb:{name:'Marketplace',code:'FB',url:'https://www.facebook.com/marketplace/'}
};
var ZONE={'Palermo':.92,'Belgrano':.9,'Recoleta':.88,'Puerto Madero':.9,'Retiro':.85,'Caballito':.85,'Villa Crespo':.82,'Colegiales':.8,'Almagro':.78,'Núñez':.76,'Chacarita':.74,'Villa Urquiza':.74,'Coghlan':.72,'Villa Ortúzar':.72,'San Telmo':.72,'Monserrat':.7,'Boedo':.7,'Villa del Parque':.7,'Villa Devoto':.7,'Saavedra':.68,'Villa Pueyrredón':.66,'Agronomía':.66,'San Cristóbal':.65,'Paternal':.62,'Monte Castro':.62,'Villa Luro':.62,'Vélez Sarsfield':.62,'Flores':.6,'Balvanera':.6,'Parque Patricios':.6,'Barracas':.55,'Liniers':.5,'Constitución':.5,'La Boca':.5,'Mataderos':.45,'Nueva Pompeya':.45,'Villa Lugano':.4,'Villa Soldati':.4};

var BASE=[
 {id:'p1',barrio:'Palermo',addr:'Honduras 4800',lat:-34.5895,lng:-58.4285,cur:'ARS',rent:1150000,exp:210000,m2:56,amb:3,dorm:2,balcon:true,cocina:true,owner:false,guar:['caucion'],mins:18,portals:['zonaprop','argenprop','meli']},
 {id:'p2',barrio:'Palermo',addr:'Gorriti 5200',lat:-34.5870,lng:-58.4312,cur:'ARS',rent:980000,exp:165000,m2:48,amb:3,dorm:2,balcon:true,cocina:true,owner:true,guar:['propietaria'],mins:42,portals:['fb','meli']},
 {id:'p3',barrio:'Palermo',addr:'Av. Santa Fe 3900',lat:-34.5836,lng:-58.4180,cur:'USD',rent:640,exp:175000,m2:62,amb:3,dorm:2,balcon:true,cocina:false,owner:false,guar:['caucion'],mins:7,portals:['zonaprop','argenprop','meli','fb']},
 {id:'p4',barrio:'Caballito',addr:'Acoyte 700',lat:-34.6150,lng:-58.4380,cur:'ARS',rent:780000,exp:140000,m2:52,amb:3,dorm:2,balcon:true,cocina:true,owner:true,guar:['caucion','propietaria'],mins:95,portals:['zonaprop','argenprop']},
 {id:'p5',barrio:'Caballito',addr:'José Bonifacio 800',lat:-34.6208,lng:-58.4435,cur:'ARS',rent:690000,exp:120000,m2:45,amb:2,dorm:1,balcon:true,cocina:false,owner:false,guar:['caucion'],mins:210,portals:['meli','argenprop']},
 {id:'p6',barrio:'Caballito',addr:'Av. Gaona 1300',lat:-34.6155,lng:-58.4490,cur:'USD',rent:520,exp:130000,m2:58,amb:3,dorm:2,balcon:true,cocina:true,owner:false,guar:['propietaria'],mins:33,portals:['zonaprop','meli']},
 {id:'p7',barrio:'Almagro',addr:'Medrano 500',lat:-34.6070,lng:-58.4205,cur:'ARS',rent:720000,exp:130000,m2:50,amb:3,dorm:2,balcon:false,cocina:true,owner:true,guar:['propietaria'],mins:15,portals:['fb']},
 {id:'p8',barrio:'Almagro',addr:'Bulnes 600',lat:-34.6085,lng:-58.4140,cur:'ARS',rent:820000,exp:150000,m2:55,amb:3,dorm:2,balcon:true,cocina:true,owner:false,guar:['caucion'],mins:400,portals:['zonaprop','argenprop','meli']},
 {id:'p9',barrio:'Villa Crespo',addr:'Thames 800',lat:-34.5985,lng:-58.4380,cur:'ARS',rent:900000,exp:175000,m2:54,amb:3,dorm:2,balcon:true,cocina:true,owner:false,guar:['caucion'],mins:60,portals:['argenprop','meli']},
 {id:'p10',barrio:'Villa Crespo',addr:'Aguirre 800',lat:-34.5950,lng:-58.4410,cur:'USD',rent:560,exp:120000,m2:49,amb:2,dorm:1,balcon:false,cocina:true,owner:true,guar:['propietaria'],mins:5,portals:['fb','meli']},
 {id:'p11',barrio:'Belgrano',addr:'Av. Cabildo 2100',lat:-34.5628,lng:-58.4562,cur:'ARS',rent:1380000,exp:260000,m2:68,amb:3,dorm:2,balcon:true,cocina:true,owner:false,guar:['caucion'],mins:130,portals:['zonaprop','argenprop','meli']},
 {id:'p12',barrio:'Belgrano',addr:'Superí 1700',lat:-34.5640,lng:-58.4515,cur:'USD',rent:750,exp:220000,m2:64,amb:3,dorm:2,balcon:true,cocina:false,owner:false,guar:['caucion','propietaria'],mins:25,portals:['zonaprop','fb']},
 {id:'p13',barrio:'Recoleta',addr:'Junín 1500',lat:-34.5880,lng:-58.3970,cur:'ARS',rent:1050000,exp:null,m2:46,amb:2,dorm:1,balcon:true,cocina:true,owner:false,guar:['caucion'],mins:300,portals:['meli']},
 {id:'p14',barrio:'Colegiales',addr:'Zapiola 600',lat:-34.5745,lng:-58.4490,cur:'ARS',rent:960000,exp:150000,m2:58,amb:3,dorm:2,balcon:true,cocina:true,owner:true,guar:['propietaria'],mins:11,portals:['fb','argenprop']},
 {id:'p15',barrio:'Núñez',addr:'Av. Crámer 3300',lat:-34.5500,lng:-58.4590,cur:'ARS',rent:1200000,exp:190000,m2:60,amb:3,dorm:2,balcon:true,cocina:true,owner:false,guar:['caucion'],mins:520,portals:['meli','zonaprop']},
 {id:'p16',barrio:'San Telmo',addr:'Chacabuco 800',lat:-34.6205,lng:-58.3730,cur:'USD',rent:480,exp:90000,m2:44,amb:2,dorm:1,balcon:false,cocina:false,owner:false,guar:['propietaria'],mins:45,portals:['argenprop','fb']},
 {id:'p17',barrio:'Flores',addr:'Av. Directorio 1500',lat:-34.6325,lng:-58.4560,cur:'ARS',rent:560000,exp:80000,m2:52,amb:3,dorm:2,balcon:true,cocina:true,owner:true,guar:['propietaria'],mins:180,portals:['fb','meli']},
 {id:'p18',barrio:'Palermo',addr:'Honduras 4800',lat:-34.5891,lng:-58.4281,cur:'ARS',rent:1450000,exp:230000,m2:71,amb:4,dorm:3,balcon:true,cocina:true,owner:false,guar:['caucion'],mins:20,portals:['zonaprop']}
];

function hashStr(s){var h=2166136261;for(var i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return h>>>0;}
function rng(seed){var s=seed>>>0||1;return function(){s=(Math.imul(s,1664525)+1013904223)>>>0;return s/4294967296;};}

/* Cada portal publica el mismo depto con pequeñas diferencias: ubicación aproximada, m² y expensas. */
function variant(p,portal,i){
  var r=rng(hashStr(p.id+portal));
  return {
    rid:p.id+'-'+portal, portal:portal, barrio:p.barrio, addr:p.addr,
    lat:+(p.lat+(r()-.5)*0.0005).toFixed(6), lng:+(p.lng+(r()-.5)*0.0005).toFixed(6),
    cur:p.cur, rent:p.rent, exp:(portal==='meli'?null:p.exp),
    m2:Math.round(p.m2*(1+(r()-.5)*0.05)), amb:p.amb, dorm:p.dorm,
    balcon:p.balcon, cocina:p.cocina, owner:(p.owner&&portal!=='zonaprop'), guar:p.guar,
    mins:Math.max(1,Math.round(p.mins*(1-0.12*i))),
    photos:null, url:PORTALS[portal].url
  };
}
var SAMPLE_RAW=[];
BASE.forEach(function(p){p.portals.forEach(function(pt,i){SAMPLE_RAW.push(variant(p,pt,i));});});
if(typeof module!=='undefined'){module.exports={SAMPLE_RAW:SAMPLE_RAW,PORTALS:PORTALS,ZONE:ZONE};}
