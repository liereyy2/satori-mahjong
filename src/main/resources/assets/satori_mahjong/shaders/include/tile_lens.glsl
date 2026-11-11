
vec4 satoriLensProject(mat4 projection, vec4 position, vec2 lens) {
    vec4 physical = projection * position;
    if (lens.x == 1.0 && lens.y == 0.0) return physical;
    position.z -= lens.y;
    vec4 optical = projection * position;
    optical.xy *= lens.x;
    optical.z = physical.z * optical.w / physical.w;
    return optical;
}
