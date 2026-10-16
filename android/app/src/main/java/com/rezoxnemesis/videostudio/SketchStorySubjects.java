package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import static com.rezoxnemesis.videostudio.SketchStoryFilmGenerator.*;

/**
 * Authored native vector subject archetypes. Every component has an editable
 * stable ID; wheels, wings, heads, limbs and atmospheric effects move separately.
 * No downloaded art, arbitrary media attachment or neural shape invention.
 */
final class SketchStorySubjects {
    private SketchStorySubjects(){}

    static void vehicle(JSONObject hero,JSONObject environment,int start,int span,int act,SketchStoryFilmGenerator.Palette p)throws Exception {
        double c=act==1?.47:.37,pivot=.735;
        double[] x=act==0?new double[]{-.08,-.04,0,.06,.12}:act==1?
                new double[]{.10,.066,.025,-.013,-.039}:new double[]{-.12,-.06,.018,.12,.205};
        double[] y=act==0?new double[]{0,-.003,.004,-.005,0}:act==1?
                new double[]{0,-.012,.019,-.009,.008}:new double[]{.008,0,-.01,-.004,0};
        double[] tilt=act==0?new double[]{0,.7,-.8,1,0}:act==1?
                new double[]{-1,-2,-7,-2,0}:new double[]{0,2,1,-1,0};
        add(environment,oval("car_ground_shadow_"+act,p.deep,start,span,c-.247,.89,.496,.032));
        JSONObject body=gradient(poly("car_body_"+act,p.coatLight,start,span,
                c-.235,.766,c-.213,.719,c-.149,.706,c-.084,.629,
                c+.075,.629,c+.136,.697,c+.223,.717,c+.236,.782,
                c+.216,.848,c-.218,.848),p.coatLight,p.coat);
        JSONObject valance=rect("car_lower_valance_"+act,p.ink,start,span,c-.236,.811,.47,.039);
        JSONObject roof=poly("car_roof_"+act,p.coat,start,span,c-.134,.706,c-.079,.64,c+.070,.64,c+.129,.711);
        JSONObject glass=gradient(poly("car_windshield_"+act,p.far,start,span,
                c-.077,.65,c+.058,.65,c+.108,.706,c-.116,.706),p.light,p.far);
        JSONObject stripe=poly("car_stripe_"+act,p.flare,start,span,c-.16,.752,c+.2,.753,c+.188,.769,c-.169,.766);
        JSONObject trim=line("car_window_trim_"+act,p.ink,start,span,c+.003,.649,c+.003,.704,.004);
        JSONObject door=line("car_door_seam_"+act,p.ink,start,span,c+.046,.717,c+.04,.84,.003);
        JSONObject headlight=rect("car_headlight_"+act,p.light,start,span,c+.201,.765,.027,.019);
        JSONObject tail=rect("car_tail_light_"+act,p.flare,start,span,c-.232,.767,.018,.025);
        JSONObject skirt=gradient(rect("car_side_skirt_"+act,p.coat,start,span,c-.175,.82,.34,.016),p.coatLight,p.deep);
        JSONObject handle=rect("car_door_handle_"+act,p.light,start,span,c+.027,.745,.032,.008);
        for(JSONObject part:new JSONObject[]{body,valance,roof,glass,stripe,trim,door,headlight,tail,skirt,handle}){
            motion(part,start,span,c,pivot,x,y,tilt);
            add(hero,part);
        }
        for(int wheel=0;wheel<2;wheel++){
            double cx=c+(wheel==0?-.144:.145),cy=.846;
            JSONObject tyre=oval("car_tyre_"+act+"_"+wheel,p.ink,start,span,cx-.058,cy-.063,.116,.126);
            JSONObject rim=gradient(oval("car_rim_"+act+"_"+wheel,p.far,start,span,cx-.038,cy-.041,.076,.082),p.light,p.city);
            JSONObject hub=oval("car_hub_"+act+"_"+wheel,p.ink,start,span,cx-.015,cy-.015,.03,.03);
            JSONObject spokeA=line("car_spoke_a_"+act+"_"+wheel,p.light,start,span,cx-.034,cy,cx+.034,cy,.006);
            JSONObject spokeB=line("car_spoke_b_"+act+"_"+wheel,p.light,start,span,cx,cy-.035,cx,cy+.035,.006);
            double[] roll=act==1?new double[]{0,49,69,74,80}:new double[]{0,68,145,218,301};
            double[] noRoll={0,0,0,0,0};
            motion(tyre,start,span,cx,cy,x,y,noRoll);
            motion(rim,start,span,cx,cy,x,y,roll);
            motion(hub,start,span,cx,cy,x,y,noRoll);
            motion(spokeA,start,span,cx,cy,x,y,roll);
            motion(spokeB,start,span,cx,cy,x,y,roll);
            addAll(hero,tyre,rim,spokeA,spokeB,hub);
        }
        for(int n=0;n<6;n++){
            double px=c-.253-n*.018,py=.8-(n%3)*.017;
            JSONObject streak=line("car_speed_trail_"+act+"_"+n,p.rain,start,span,px,py,px-.029,py+.007,.002);
            motion(streak,start,span,px,py,new double[]{0,-.02,-.05,-.09,-.14},
                    new double[]{0,.002,-.004,.007,0},new double[]{0,0,0,0,0});
            add(hero,streak);
        }
    }

    static void dragon(JSONObject hero,JSONObject environment,int start,int span,int act,SketchStoryFilmGenerator.Palette p)throws Exception {
        double c=act==1?.49:.46;
        double[] x=act==0?new double[]{-.12,-.06,0,.06,.12}:act==1?
                new double[]{.08,.04,0,-.03,-.01}:new double[]{-.10,-.06,.01,.09,.16};
        double[] y=act==0?new double[]{.01,-.012,-.026,-.008,.012}:act==1?
                new double[]{-.005,.02,.052,.03,.01}:new double[]{.028,.008,-.018,-.03,-.045};
        double[] turn=act==0?new double[]{-3,2,-2,1,-4}:act==1?
                new double[]{6,15,24,11,0}:new double[]{0,-6,-3,1,-4};
        add(environment,oval("dragon_ground_shadow_"+act,p.deep,start,span,c-.25,.915,.50,.028));
        JSONObject farWing=gradient(poly("dragon_wing_back_"+act,p.far,start,span,
                c-.07,.605,c-.27,.26,c-.20,.42,c-.14,.37,c-.085,.55,c+.055,.645),p.far,p.deep);
        motion(farWing,start,span,c-.06,.606,x,y,new double[]{-12,29,-32,28,-18});
        add(hero,farWing);
        JSONObject tail=gradient(poly("dragon_tail_"+act,p.coat,start,span,
                c-.12,.655,c-.27,.665,c-.37,.735,c-.29,.682,c-.27,.622,c-.12,.612),p.coatLight,p.coat);
        motion(tail,start,span,c-.11,.645,x,y,new double[]{-7,6,-9,12,-4});add(hero,tail);
        JSONObject torso=gradient(oval("dragon_torso_"+act,p.coatLight,start,span,c-.14,.55,.29,.21),p.coatLight,p.coat);
        JSONObject belly=gradient(oval("dragon_belly_"+act,p.skinShadow,start,span,c-.065,.666,.19,.078),p.skin,p.skinShadow);
        motion(torso,start,span,c,.66,x,y,turn);motion(belly,start,span,c,.66,x,y,turn);
        addAll(hero,torso,belly);
        for(int n=0;n<6;n++){
            double px=c-.099+n*.042,py=.552+Math.sin(n*.65)*.012;
            JSONObject spine=poly("dragon_spine_"+act+"_"+n,p.ink,start,span,
                    px-.015,py+.018,px,py-.040,px+.025,py+.020);
            motion(spine,start,span,c,.66,x,y,turn);add(hero,spine);
        }
        JSONObject neck=gradient(poly("dragon_neck_"+act,p.coat,start,span,
                c+.095,.65,c+.12,.485,c+.20,.48,c+.178,.665),p.coatLight,p.coat);
        JSONObject head=gradient(oval("dragon_head_"+act,p.coatLight,start,span,c+.11,.443,.165,.14),p.coatLight,p.far);
        JSONObject muzzle=poly("dragon_muzzle_"+act,p.coat,start,span,c+.21,.516,c+.348,.541,c+.329,.577,c+.212,.571);
        JSONObject jaw=poly("dragon_jaw_"+act,p.skinShadow,start,span,c+.206,.568,c+.332,.577,c+.279,.599,c+.215,.579);
        for(JSONObject part:new JSONObject[]{neck,head,muzzle,jaw}){motion(part,start,span,c+.13,.54,x,y,turn);add(hero,part);}
        JSONObject eye=oval("dragon_eye_"+act,p.flare,start,span,c+.236,.494,.017,.022);
        JSONObject pupil=oval("dragon_pupil_"+act,p.ink,start,span,c+.244,.499,.007,.016);
        JSONObject hornA=poly("dragon_horn_left_"+act,p.ink,start,span,c+.139,.481,c+.152,.387,c+.188,.455);
        JSONObject hornB=poly("dragon_horn_right_"+act,p.ink,start,span,c+.199,.46,c+.248,.389,c+.227,.482);
        for(JSONObject part:new JSONObject[]{eye,pupil,hornA,hornB}){motion(part,start,span,c+.13,.54,x,y,turn);add(hero,part);}
        for(int n=0;n<2;n++){
            double px=c+(n==0?-.078:.055);
            JSONObject leg=poly("dragon_leg_"+act+"_"+n,p.coat,start,span,px,.725,px+.032,.733,px+.053,.836,px+.005,.85);
            JSONObject claw=line("dragon_claw_"+act+"_"+n,p.light,start,span,px+.02,.837,px+.069,.855,.006);
            double[] kick=n==0?new double[]{8,-11,9,-13,5}:new double[]{-12,9,-14,7,-6};
            motion(leg,start,span,px,.74,x,y,kick);motion(claw,start,span,px,.74,x,y,kick);
            addAll(hero,leg,claw);
        }
        JSONObject frontWing=gradient(poly("dragon_wing_front_"+act,p.coatLight,start,span,
                c-.045,.605,c-.07,.225,c+.005,.386,c+.107,.301,c+.072,.475,c+.105,.635),p.coatLight,p.coat);
        motion(frontWing,start,span,c+.011,.612,x,y,new double[]{-25,24,-39,29,-26});
        add(hero,frontWing);
        for(int n=0;n<3;n++){
            double py=.567+n*.015,px=c+.332+n*.024;
            JSONObject flame=oval("dragon_breath_"+act+"_"+n,n==1?p.light:p.flare,
                    start,span,px,py,.042+n*.015,.022+n*.009);
            motion(flame,start,span,c+.35,py,new double[]{0,.015,.039,.07,.112},
                    new double[]{0,-.005,.009,-.008,-.01},new double[]{0,-5,2,-4,0});
            add(hero,flame);
        }
    }
}
