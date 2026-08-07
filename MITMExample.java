import java.util.ArrayList;
import java.util.HashMap;


/*
    Program the reverse engineer the lower 48 bits of a 64 bit minecraft seed using structures generated on said seed,
    the relevant source code can be found in the cubiomes (non viewer repository) library on github under finders.c
    or can be derived from the furtherfilter method here.
    Normal bruteforce would be O(2^48), however there is multiple methods to reduce the bruteforce
    one such method is lifting, it however only works on multiples of 2 (or optimally greater powers of 2) as the modulus for the nextInt() call.
    Another method is to use the property that n+m is congruent to n mod m, this allows for an m times speedup, that method
    however can be further optimized. Since a structure chooses 2 relative coordinates you can simplify the advancement
    of the LCG state into an addition and find a multiple of the modulo, which if you add it to the state of the first
    coordinate, causes an addition to the state of the second coordinate, that is a multiple of the same bound.
    To combat the modulo 2^48 which sometimes breaks the congruency another multiple of 25 value can be found that reverses
    the effects, this can be done for both coordinates.
    This however leaves you with a maximum optimization of the non guaranteed lifting step and modulo^2 speedup
    in one of the worst cases, such are ruined portals, this leaves you with only a 625x speedup, which results in
    a time complexity O(2^48-log2(625)) or about O(38.71).
    However there is a nice property of the LCG. When splitting the state into 2 and calculating the results of the
    nextInt() call for both of them, adding these together will results in either the exact same value,
    or the exact same value off by 2 (which is due to both states combined exceeding 2^48).
    Since you can get the states of the LCG during this step, one can build a lookup table on a part of the LCG, calculate
    the contributions to (in this case) mod 25 and store it for both coordinates (more on that later). Then, one could iterate
    over the other half, calculate their contributions, and fetch the required values from the lookup table.
    (Important is to only do the +11 on one half, since doing it on both will cause an effect of adding the multiplier*11 to the second coordinates state)
    Since it can be off by 2 for each coordinate, you will need to account for all 4 possibilities.
    This however results in the same if not a larger bruteforce than the previous approach.
    The difference here however is, that since the XOR by the multiplier after the regionseeding destroys and modular congruence for
    the previous approach, it is only a negligable problem in this case.
    Since the XOR itself does not directly destroy the approach, and in this case the problem is,
    that the regionseeding can have a carry into the upper bits.
    What one can do is, index the LUT based on 3 such structures each with both of their coordinates
    (from experiments I concluded this is the most optimal configuration)
    and whether for each of the 3 structures, the regionseeding causes a carry.
    Then, when iterating over the other half, one can test each carry possibility and test each possible way both matching states could overflow,
    fetch the results from the lookup table indexed the carry caused and under the required coordinates based on the overflow and
    check whether adding all matching states (which have been stored alongside the partial seeds in the LUT) together
    would cause the same overflow that was being tested.
    Finally, when a valid result is required it can be further filtered using simple forward checking to reduce the candidates.
 */

//This implementation is only made for modulo 25 and a fixed LUT but can easily be adapted to accept other moduli and a different LUT size
public class MITMExample {
    public static int Results = 0;
    public static int targetX, targetZ;
    public static int targetX2, targetZ2;
    public static int targetX3, targetZ3;
    public static int ModulotargetX1, ModulotargetZ1;
    public static int ModulotargetX2, ModulotargetZ2;
    public static int ModulotargetX3, ModulotargetZ3;
    public static ArrayList<Integer> coordsX = new ArrayList<>();
    public static ArrayList<Integer> coordsZ = new ArrayList<>();
    public static ArrayList<Long> regX = new ArrayList<>();
    public static ArrayList<Long> regZ = new ArrayList<>();
    public static int portalamount = 0;
    public static HashMap<Long, ArrayList<long[]>> LUT = new HashMap<>();

    public static void main(String[] args) {
        ruinedPortalInformation();
        ModulotargetX1 = ((targetX %40)+40)%40; ModulotargetZ1 = ((targetZ %40)+40)%40;
        ModulotargetX2 = ((targetX2 %40)+40)%40; ModulotargetZ2 = ((targetZ2 %40)+40)%40;
        ModulotargetX3 = ((targetX3 %40)+40)%40; ModulotargetZ3 = ((targetZ3 %40)+40)%40;
        buildLUT();
        MeetInTheMiddle();
        System.out.println("Amount of results: "+ Results);

    }
    //Encodes the coordinates to a key for the hashmap
    public static long encodeCoordinates(long x1, long z1, long x2, long z2, long x3, long z3, long carry) {
        long key = x1;
        key = key * 25 + z1;
        key = key * 25 + x2;
        key = key * 25 + z2;
        key = key * 25 + x3;
        key = key * 25 + z3;
        key = key * 8 + carry;
        return key;
    }
    //Builds the lookup table from lower 24 bits indexed on the carry pattern and the coordinates, also stores their states for the overflow pattern
    public static void buildLUT(){
        for(long lower24 = 0;lower24<(1L<<24);lower24++){
            ReducedRandom.setPartialSeedWithRegion(lower24,24,0,0,regX.get(0),regZ.get(0));
            long x1 = ReducedRandom.nextInt(25);
            long sx1 = ReducedRandom.getState();
            long z1 = ReducedRandom.nextInt(25);
            long sz1 = ReducedRandom.getState();
            long carry = ReducedRandom.getCarry(lower24,regX.get(0),regZ.get(0),24);
            ReducedRandom.setPartialSeedWithRegion(lower24,24,0,0,regX.get(1),regZ.get(1));
            long x2 = ReducedRandom.nextInt(25);
            long sx2 = ReducedRandom.getState();
            long z2 = ReducedRandom.nextInt(25);
            long sz2 = ReducedRandom.getState();
            carry += ReducedRandom.getCarry(lower24,regX.get(1),regZ.get(1),24)*2;
            ReducedRandom.setPartialSeedWithRegion(lower24,24,0,0,regX.get(2),regZ.get(2));
            long x3 = ReducedRandom.nextInt(25);
            long sx3 = ReducedRandom.getState();
            long z3 = ReducedRandom.nextInt(25);
            long sz3 = ReducedRandom.getState();
            carry += ReducedRandom.getCarry(lower24,regX.get(2),regZ.get(2),24)*4;
            long key = encodeCoordinates(x1,z1,x2,z2,x3,z3,carry);
            ArrayList<long[]> tempLUTList = new ArrayList<long[]>();
            if(LUT.containsKey(key)){
                tempLUTList = LUT.get(key);
            }
            long[] tempLUTValues = new long[7];
            tempLUTValues[0] = lower24;
            tempLUTValues[1] = sx1;
            tempLUTValues[2] = sz1;
            tempLUTValues[3] = sx2;
            tempLUTValues[4] = sz2;
            tempLUTValues[5] = sx3;
            tempLUTValues[6] = sz3;
            tempLUTList.add(tempLUTValues);
            LUT.put(key, tempLUTList);
            if(lower24%16777==0){
                System.out.println(lower24 + "/16777216 for LUT building");
            }
        }
    }

    //Meet in the middle, iterates over carry and the upper 24 bits, computes the required coordinates,
    //offsets them according to all iterated overflow patterns and fetches results from the LUT
    //Ends by checking whether the result matches the overflow pattern that is being iterated over
    public static void MeetInTheMiddle(){
        for(int carrypattern = 0; carrypattern <8; carrypattern++){
            for(long upper24 = 0; upper24<(1L<<24);upper24++){
                long actualupper24 = (upper24 << 24);
                ReducedRandom.setPartialSeedWithRegion(actualupper24, 48, 24, (carrypattern & 1), regX.get(0), regZ.get(0));
                long x1 = ReducedRandom.nextIntUpper(25);
                long sx1 = ReducedRandom.getState();
                long z1 = ReducedRandom.nextIntUpper(25);
                long sz1 = ReducedRandom.getState();
                ReducedRandom.setPartialSeedWithRegion(actualupper24, 48, 24, (carrypattern & 2)>>1, regX.get(1), regZ.get(1));
                long x2 = ReducedRandom.nextIntUpper(25);
                long sx2 = ReducedRandom.getState();
                long z2 = ReducedRandom.nextIntUpper(25);
                long sz2 = ReducedRandom.getState();
                ReducedRandom.setPartialSeedWithRegion(actualupper24, 48, 24, (carrypattern & 4)>>2, regX.get(2), regZ.get(2));
                long x3 = ReducedRandom.nextIntUpper(25);
                long sx3 = ReducedRandom.getState();
                long z3 = ReducedRandom.nextIntUpper(25);
                long sz3 = ReducedRandom.getState();
                /*This is hardcoded since its modulo 25, the -2 are from the overflow and derived from 2^31 modulo 25, the -7 is derived from the combined addend since the addition of
                  11 results in a greater result after the second state advancement due to the the multiplier
                  The -9 is derived from both
                */
                long reqx1 = ((((ModulotargetX1 - x1)) % 25) + 25) % 25;
                long reqx1o = (((((ModulotargetX1 - x1)) - 2) % 25) + 25) % 25;
                long reqz1 = ((((ModulotargetZ1 - z1)) % 25) + 25) % 25;
                long reqz1o = ((((ModulotargetZ1 - z1) - 2) % 25) + 25) % 25;
                long reqx2 = (((ModulotargetX2 - x2) % 25) + 25) % 25;
                long reqx2o = ((((ModulotargetX2 - x2) - 2) % 25) + 25) % 25;
                long reqz2 = ((((ModulotargetZ2 - z2)) % 25) + 25) % 25;
                long reqz2o = ((((ModulotargetZ2 - z2) - 2) % 25) + 25) % 25;
                long reqx3 = (((ModulotargetX3 - x3) % 25) + 25) % 25;
                long reqx3o = ((((ModulotargetX3 - x3) - 2) % 25) + 25) % 25;
                long reqz3 = ((((ModulotargetZ3 - z3)) % 25) + 25) % 25;
                long reqz3o = ((((ModulotargetZ3 - z3) - 2) % 25) + 25) % 25;
                //Since each value can have an overflow i calculated 2 versions for each value if the 2 states exceed 2^48 an overflow has occured
                for(int overflowpattern = 0; overflowpattern<64;overflowpattern++){
                    long reqx1a = (overflowpattern&1) == 0 ? reqx1 : reqx1o;
                    long reqz1a = (overflowpattern&2) == 0 ? reqz1 : reqz1o;
                    long reqx2a = (overflowpattern&4) == 0 ? reqx2 : reqx2o;
                    long reqz2a = (overflowpattern&8) == 0 ? reqz2 : reqz2o;
                    long reqx3a = (overflowpattern&16) == 0 ? reqx3 : reqx3o;
                    long reqz3a = (overflowpattern&32) == 0 ? reqz3 : reqz3o;
                    if(!LUT.containsKey(encodeCoordinates(reqx1a,reqz1a,reqx2a,reqz2a,reqx3a,reqz3a,carrypattern))){continue;}
                    ArrayList<long[]> tempMITMList = LUT.get(encodeCoordinates(reqx1a,reqz1a,reqx2a,reqz2a,reqx3a,reqz3a,carrypattern));
                    for(int i = 0; i<tempMITMList.size();i++){
                        long[] tempMITMValues = tempMITMList.get(i);
                        long lower24 = tempMITMValues[0];
                        long overflowmask = 0; //This value will be calculated using what overflows occured with the looked up combination, and will be checked against overflowpattern
                        if(sx1+tempMITMValues[1]>=(1L<<48)){overflowmask+=1;}
                        if(sz1+tempMITMValues[2]>=(1L<<48)){overflowmask+=2;}
                        if(sx2+tempMITMValues[3]>=(1L<<48)){overflowmask+=4;}
                        if(sz2+tempMITMValues[4]>=(1L<<48)){overflowmask+=8;}
                        if(sx3+tempMITMValues[5]>=(1L<<48)){overflowmask+=16;}
                        if(sz3+tempMITMValues[6]>=(1L<<48)){overflowmask+=32;}
                        if(overflowmask==overflowpattern){
                            long seed = lower24+actualupper24;
                            furtherFilter(seed);
                        }
                    }
                }
                if(upper24%167772==0){
                    System.out.println(upper24 +(16777216*carrypattern) + "/"+16777216*8+" for MITM");
                }
            }
        }
    }

    //Input ruined portal information here
    public static void ruinedPortalInformation() {
        addPortal(40, 40);
        addPortal(8, 20);
        addPortal(50, 5);

        addPortal(14, 41);

        addPortal(95, 57);
        addPortal(160, 12);
        addPortal(128, 15);
        addPortal(84, 15);
        addPortal(142, 49);
        addPortal(141, 83);
        addPortal(180, 64);




    }

    //Helper method to make inputting data easier
    public static void addPortal(long chunkX, long chunkZ) {

        long regXhere = (chunkX >= 0 ? chunkX / 40 : ((chunkX + 1) / 40 - 1));
        long regZhere = (chunkZ >= 0 ? chunkZ / 40 : ((chunkZ + 1) / 40 - 1));
        regX.add(regXhere);
        regZ.add(regZhere);

        int localX = (((int)chunkX % 40) + 40) % 40;
        int localZ = (((int)chunkZ % 40) + 40) % 40;
        coordsX.add(localX);
        coordsZ.add(localZ);
        if (portalamount == 0) {
            targetX = (int)chunkX;
            targetZ = (int)chunkZ;
        }
        if (portalamount == 1) {
            targetX2 = (int)chunkX;
            targetZ2 = (int)chunkZ;
        }
        if (portalamount == 2) {
            targetX3 = (int)chunkX;
            targetZ3 = (int)chunkZ;
        }
        portalamount++;
    }

    //Final filter using the data that was not used by the LUT
    public static void furtherFilter(long seed) {
        //The LCG is mod 2^48, which is why you can only recover the lower 48 bits
        final long mask = (1L << 48) - 1;
        //The multiplier of the LCG
        final long mul = 25214903917L;
        //The addition of the LCG
        final long add = 11L;

        for (int i = 3; i < portalamount; i++) {
            //regionseeding based on the region coordinates and known constants
            long fullseed = (seed + regX.get(i) * 341873128712L + regZ.get(i) * 132897987541L + 34222645) & mask;
            //setseed
            long state = fullseed ^ 25214903917L;

            //nextInt(25) for x
            state = (state * mul + add) & mask;
            long x = ((state >> 17) % 25 + 25) % 25;
            //nextInt(25) for z
            state = (state * mul + add) & mask;
            long z = ((state >> 17) % 25 + 25) % 25;

            //check wether it matches for the given coordinates
            if (!(x == coordsX.get(i) && z == coordsZ.get(i))) {
                return;
            }
        }

        //System.out.println("\nCandidate fullseed: " + seed);
        Results++;
    }

    //Helper method for partial random calls and seedings
    static class ReducedRandom {
        private static long state;

        //This exists because i want it to exist for code readability
        public void setState(long s) { state = s; }
        /*This applies the regionseeding with the bits you want it to, you can set bits to 24 and discard to 0 for the lower 24
          and set bits to 48 and discard to 48 for the upper24, i didnt need more for the implementation of hte algorithm
        */
        public static void setPartialSeedWithRegion(long seed, long bits, long discard, int carry, long regX, long regZ) {
            state = (((((seed + regX * 341873128712L + regZ * 132897987541L + 34222645L + ((long)carry << discard)) ^ 25214903917L)
                    & ((1L << bits) - 1)) >> discard) << discard);
        }
        //this advances the LCGs state
        public static void next() {
            state = (state * 25214903917L + 11) & ((1L << 48) - 1);
        }
        //simplified version of javas nextInt() accurate enough for the actual bruteforce. The chance the algorithm fails is to incredibly low it probably wont ever happen during practical use.
        public static long nextInt(int bound) {
            next();
            return (((state >> 17) % bound) + bound) % bound;
        }
        //this advances the LCGs state, the upper need a different calculation since the 11 is already added to the lower bits and is part of the calculation for its contribution
        public static void nextUpper() {
            state = (state * 25214903917L) & ((1L << 48) - 1);
        }
        //simplified version of javas nextInt() accurate enough for the actual bruteforce. The chance the algorithm fails is to incredibly low it probably wont ever happen during practical use.
        //Upper bits use nextUpper instead to avoid doubling the addition of 11
        public static long nextIntUpper(int bound) {
            nextUpper();
            return (((state >> 17) % bound) + bound) % bound;
        }
        //This exists because i want it to exist for code readability aswell
        public static long getState() { return state; }

        //This is important to generate the carry patterns when building the LUT
        public static long getCarry(long lowerPart, long regX, long regZ, long bits) {
            long base = regX * 341873128712L + regZ * 132897987541L + 34222645L;
            long top1 = (lowerPart + base) >> bits;
            long top2 = base >> bits;
            return top1 - top2;
        }
    }
}