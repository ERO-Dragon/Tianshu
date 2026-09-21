/*
    "Siri" by @XorDev

    https://x.com/XorDev/status/1955363029505413337
    
    <512 playlist:
    https://www.shadertoy.com/playlist/N3SyzR

    Twigl code:
    vec3 p,a;
    for(float i,z,d,s;z+i++<2e2;
        o+=max(cos(p.x*.4+vec4(0,2,4,0)),5./s/s)/d/d)
        p=z*normalize(FC.rgb*2.-r.xyy),p.z+=9.,
        s=length(p=dot(a=normalize(cos(vec3(0,2,4)-t*.5+s*.3)),p)*a-cross(a,p)),
        z+=d=min(abs(dot(p,sin(p).yzx))*.2+max(d=s-5.,.1),abs(--d)+.2)*.2;
    o=tanh(o/3e4);
*/

void mainImage(out vec4 O, vec2 I)
{
    //3D sample point and rotation direction
    vec3 p,a;
    
    //Raymarch depth
    float z,
    //Step distance
    d,
    //Sphere distance
    s,
    //Raymarch iterator
    i;
    
    //Clear fragcolor and raymarch 200 steps
    for(O*=i; i++<2e2;
        //Coloring and brightness
        O += max(cos(p.x*.6+vec4(0,2,4,0)),5./s/s)/d/d)
        
        //Raymarch sample point
        p = z*normalize(vec3(I+I,0)-iResolution.xyy),
        //Move back 9 units
        p.z += 9.,
        //Distance to center
        s = length(
        //Rotate and twist
        p = dot(a = normalize(cos(vec3(0,2,4)-iTime*.5+s*.3)),p)*a - cross(a,p)),
        //Swirly orb distance field
        z += d = min(abs(dot(p,sin(p).yzx))*.2+max(d=s-5.,.1),abs(--d)+.2)*.2;
    //Tanh tonemap
    O = tanh(O/3e4);
}