// app/(tabs)/consumer-home-screen.tsx
import React from 'react';
import { StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import ConsumerHomeScreen from '../../src/features/consumer/dashboard/screens/ConsumerHomeScreen';

export default function ConsumerHomeRoute() {
  return (
    <SafeAreaView style={styles.safeArea} edges={['top']}>
      <ConsumerHomeScreen />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: 'white',
  },
});