import { Inter_400Regular, Inter_500Medium, Inter_600SemiBold, Inter_700Bold } from '@expo-google-fonts/inter';
import { useFonts } from 'expo-font';
import { useRouter } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { useEffect, useRef } from 'react';
import { Animated, Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { authStyles } from '../../src/features/auth/authStyles';

export default function App() {
  const [loaded] = useFonts({
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
    Inter_700Bold,
  });

  const titleOpacity = useRef(new Animated.Value(0)).current;
  const buttonOpacity = useRef(new Animated.Value(0)).current;
  const logo = require('../../assets/images/OrionLogo.png');
  const router = useRouter();

  // Handle role selection and pass it safely to the sign-in screen params
  const handleRoleSelect = (role: 'business' | 'consumer') => {
    router.push({
      pathname: '/sign-in',
      params: { type: role },
    });
  };

  useEffect(() => {
    if (loaded) {
      const run = async () => {
        await new Promise(res => setTimeout(res, 2000));
        await SplashScreen.hideAsync();
        router.replace('/consumer-home-screen');
      };

      run();

      Animated.sequence([
        Animated.timing(titleOpacity, {
          toValue: 1,
          duration: 500,
          useNativeDriver: true,
        }),
        Animated.timing(buttonOpacity, {
          toValue: 1,
          duration: 500,
          delay: 300,
          useNativeDriver: true,
        }),
      ]).start();
    }
  }, [loaded]);

  if (!loaded) {
    return null;
  }

  return (
    <View style={authStyles.container}>
      
      <Image 
        source={logo} 
        style={[authStyles.fixedLogo, { top: 37.6 }]} 
        resizeMode="contain"
      />

      <Animated.View style={{ opacity: titleOpacity, marginTop: 120, width: '100%' }}>

        <Text style={styles.title}>How will you be using Orion?</Text>

        <View style={{ height: 3, backgroundColor: 'black', marginVertical: 20, width: '100%' }}/>

        <Animated.View style={{ opacity: buttonOpacity }}>
          <View style={styles.buttonRow}>

            {/* Business Button */}
            <Pressable
              onPress={() => handleRoleSelect('business')}
              style={({ pressed }) => [
                styles.button,
                {
                  opacity: pressed ? 0.8 : 1,
                  transform: [{ scale: pressed ? 0.97 : 1 }],
                },
              ]}
            >
              <Text style={styles.buttonText}>Business</Text>
            </Pressable>

            {/* Consumer Button */}
            <Pressable
              onPress={() => handleRoleSelect('consumer')}
              style={({ pressed }) => [
                styles.button,
                {
                  opacity: pressed ? 0.8 : 1,
                  transform: [{ scale: pressed ? 0.97 : 1 }],
                },
              ]}
            >
              <Text style={styles.buttonText}>Consumer</Text>
            </Pressable>

          </View>
        </Animated.View>

      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  title: {
    fontSize: 22,
    fontFamily: 'Inter_600SemiBold',
    color: 'black',
    letterSpacing: 0.5,
    marginBottom: 5,
    textAlign: 'center',
    alignSelf: 'center'
  },
  buttonRow: {
    flexDirection: 'row',
    width: '100%',
    gap: 12,
    paddingHorizontal: 6,
  },
  button: {
    paddingVertical: 16,
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
    backgroundColor: 'white',
    borderWidth: 2,
    borderColor: 'black'
  },
  buttonText: {
    color: 'black',
    fontSize: 16,
    fontFamily: 'Inter_600SemiBold',
    textAlign: 'center'
  }
});