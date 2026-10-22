// Mapping matches SettingsRepository.Keys. Input is an approved public settings
// export supplied locally, never a live Room/Firestore connection or backup ZIP.
export function publicSettings(settings={}){
 const get=(key,max)=>typeof settings[key]==='string'?settings[key].trim().slice(0,max).replace(/[\u0000-\u001f]/gu,''):'';
 return {PUBLIC_SHOP_NAME:get('shop_name',100)||'HardwareShopPOS',PUBLIC_SHOP_PHONE:get('shop_phone',30),PUBLIC_SHOP_ADDRESS:get('shop_address',300)};
}
