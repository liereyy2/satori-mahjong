vec3 satoriDecodeDirection(vec2 encoded) {
    vec3 n=vec3(encoded,1.0-abs(encoded.x)-abs(encoded.y));
    float t=max(-n.z,0.0);n.xy+=mix(vec2(t),vec2(-t),greaterThanEqual(n.xy,vec2(0.0)));
    return normalize(n);
}
